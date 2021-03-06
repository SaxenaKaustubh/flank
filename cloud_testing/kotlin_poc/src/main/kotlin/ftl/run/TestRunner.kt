package ftl.run

import com.google.api.services.testing.model.TestDetails
import com.google.api.services.testing.model.TestMatrix
import com.google.cloud.storage.Storage
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import ftl.config.FtlConstants
import ftl.config.FtlConstants.localhost
import ftl.config.YamlConfig
import ftl.gc.*
import ftl.json.MatrixMap
import ftl.json.SavedMatrix
import ftl.reports.CostSummary
import ftl.reports.ResultSummary
import ftl.util.*
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.stream.Collectors

object TestRunner {
    private val gson = GsonBuilder().setPrettyPrinting().create()!!

    private fun assertMockUrl() {
        if (!FtlConstants.useMock) return
        if (!GcTesting.get.rootUrl.contains(localhost)) throw RuntimeException("expected localhost in GcTesting")
        if (!GcStorage.storageOptions.host.contains(localhost)) throw RuntimeException("expected localhost in GcStorage")
        if (!GcToolResults.service.rootUrl.contains(localhost)) throw RuntimeException("expected localhost in GcToolResults")
    }

    private suspend fun runTests(config: YamlConfig): MatrixMap {
        println("runTests")
        val stopwatch = StopWatch().start()
        assertMockUrl()

        val runGcsPath = Utils.uniqueObjectName()
        var testTargets: List<String>? = null

        if (config.testMethods.isNotEmpty()) {
            testTargets = config.testMethods.stream().map { i -> "class " + i }.collect(Collectors.toList())
        }

        // GcAndroidMatrix => GcTestMatrix
        // GcTestMatrix.execute() 3x retry => matrix id (string)
        val androidMatrix = GcAndroidMatrix.build(
                "NexusLowRes",
                "26",
                "en",
                "portrait")

        val apks = uploadApksInParallel(config, runGcsPath)

        val jobs = arrayListOf<Deferred<TestMatrix>>()
        repeat(config.shardCount) {
            jobs += async {
                GcTestMatrix.build(
                        apks.first,
                        apks.second,
                        runGcsPath,
                        androidMatrix,
                        testTargets,
                        config = config).execute()
            }
        }

        val savedMatrices = mutableMapOf<String, SavedMatrix>()

        jobs.forEach {
            val matrix = it.await()
            val matrixId = matrix.testMatrixId
            savedMatrices[matrixId] = SavedMatrix(matrix)
        }

        val matrixMap = MatrixMap(savedMatrices, runGcsPath)

        updateMatrixFile(matrixMap)

        println(FtlConstants.indent + "${savedMatrices.size} matrix ids created in ${stopwatch.check()}")
        val gcsBucket = "https://console.developers.google.com/storage/browser/" +
                config.rootGcsBucket + "/" + matrixMap.runPath
        println(FtlConstants.indent + gcsBucket)
        println()

        return matrixMap
    }

    private fun updateMatrixFile(matrixMap: MatrixMap): Path {
        val matrixIdsPath = Paths.get(FtlConstants.localResultsDir, matrixMap.runPath, FtlConstants.matrixIdsFile)
        matrixIdsPath.parent.toFile().mkdirs()
        Files.write(matrixIdsPath, gson.toJson(matrixMap.map).toByteArray())
        return matrixIdsPath
    }

    /** @return Pair(app apk, test apk) **/
    private suspend fun uploadApksInParallel(config: YamlConfig, runGcsPath: String): Pair<String, String> {
        val appApkGcsPath = async { GcStorage.uploadAppApk(config, runGcsPath) }
        val testApkGcsPath = async { GcStorage.uploadTestApk(config, runGcsPath) }

        return Pair(appApkGcsPath.await(), testApkGcsPath.await())
    }

    private fun deleteResults() {
        val resultsFile = Paths.get(FtlConstants.localResultsDir).toFile()
        resultsFile.deleteRecursively()
        resultsFile.mkdirs()
    }

    /** Refresh all in progress matrices in parallel **/
    suspend fun refreshMatrices(matrixMap: MatrixMap) {
        println("refreshMatrices")

        val jobs = arrayListOf<Deferred<TestMatrix>>()
        val map = matrixMap.map
        var matrixCount = 0
        map.forEach { matrix ->
            // Only refresh unfinished
            if (MatrixState.inProgress(matrix.value.state)) {
                matrixCount += 1
                jobs += async { GcTestMatrix.refresh(matrix.key) }
            }
        }

        if (matrixCount != 0) {
            println(FtlConstants.indent + "Refreshing ${matrixCount}x matrices")
        }

        var dirty = false
        jobs.forEach {
            val matrix = it.await()
            val matrixId = matrix.testMatrixId

            println("${matrix.state} $matrixId")

            if (map[matrixId]?.update(matrix) == true) dirty = true
        }

        if (dirty) {
            println(FtlConstants.indent + "Updating matrix file")
            updateMatrixFile(matrixMap)
        }
    }

    fun lastGcsPath(): String {
        val resultsFile = Paths.get(FtlConstants.localResultsDir).toFile()
        val scheduledRuns = resultsFile.listFiles().filter { it.isDirectory }.map { it.name }

        val fileTimePairs = scheduledRuns.map {
            val dateTimePair = it.split('.')[0].split('_')
            val date = LocalDate.parse(dateTimePair[0])
            val time = LocalTime.parse(dateTimePair[1])
            Pair(it, LocalDateTime.of(date, time))
        }.sortedByDescending { it.second }
        return fileTimePairs.first().first
    }

    /** Reads in the last matrices from the localResultsDir folder **/
    fun lastMatrices(): MatrixMap {
        val lastRun = lastGcsPath()
        println("Loading run $lastRun")
        return matrixPathToObj(lastRun)
    }

    private fun matrixPathToObj(path: String): MatrixMap {
        val json = Paths.get(FtlConstants.localResultsDir, path, FtlConstants.matrixIdsFile).toFile().readText()

        val listOfSavedMatrix = object : TypeToken<MutableMap<String, SavedMatrix>>() {}.type
        val map: MutableMap<String, SavedMatrix> = gson.fromJson(json, listOfSavedMatrix)

        return MatrixMap(map, path)
    }

    /** fetch test_result_0.xml & *.png **/
    fun fetchArtifacts(matrixMap: MatrixMap) {
        println("fetchArtifacts")
        if (FtlConstants.useMock) return
        val fields = Storage.BlobListOption.fields(Storage.BlobField.NAME)

        var dirty = false
        val filtered = matrixMap.map.values.filter {
            val finished = it.state == MatrixState.FINISHED
            val notDownloaded = !it.downloaded
            val failure = it.outcome == Outcome.failure
            finished && notDownloaded && failure
        }

        filtered.forEach { matrix ->
            val prefix = Storage.BlobListOption.prefix(matrix.gcsPathWithoutRootBucket)
            val result = GcStorage.storage.list(matrix.gcsRootBucket, prefix, fields)

            result.iterateAll().forEach({ blob ->
                val blobPath = blob.blobId.name
                if (
                        blobPath.matches(ArtifactRegex.testResultRgx) ||
                        blobPath.matches(ArtifactRegex.screenshotRgx)
                ) {
                    val downloadFile = Paths.get(FtlConstants.localResultsDir, blobPath)
                    if (downloadFile.toFile().exists()) {
                        println(FtlConstants.indent + "Already downloaded: $blobPath")
                    } else {
                        println(FtlConstants.indent + "Downloading: $blobPath")
                        downloadFile.parent.toFile().mkdirs()
                        blob.downloadTo(downloadFile)
                    }
                }
            })

            dirty = true
            matrix.downloaded = true
        }

        if (dirty) {
            println(FtlConstants.indent + "Updating matrix file")
            updateMatrixFile(matrixMap)
        }
    } // fun

    /** Synchronously poll all matrix ids until they complete **/
    fun pollMatrices(matrices: MatrixMap) {
        println("pollMatrices")
        val map = matrices.map
        val poll = matrices.map.values.filter {
            MatrixState.inProgress(it.state)
        }

        val stopwatch = StopWatch().start()
        poll.forEach {
            val matrixId = it.matrixId
            val completedMatrix = pollMatrix(matrixId, stopwatch)

            map[matrixId]?.update(completedMatrix)
        }

        poll.forEach { if (it.outcome == Outcome.failure) println(it.webLink) }
        println()

        updateMatrixFile(matrices)

        runReports(matrices)
    }

    // Used for when the matrix has exactly one test. Polls for detailed progress
    //
    // Port of MonitorTestExecutionProgress
    // gcloud-cli/googlecloudsdk/api_lib/firebase/test/matrix_ops.py
    fun pollMatrix(matrixId: String, stopwatch: StopWatch): TestMatrix {
        var lastState = ""
        var error: String?
        var progress = listOf<String>()
        var lastProgressLen = 0
        var refreshedMatrix: TestMatrix

        fun puts(msg: String) {
            val timestamp = stopwatch.check(indent = true)
            println("${FtlConstants.indent}$timestamp $matrixId $msg")
        }

        while (true) {
            refreshedMatrix = GcTestMatrix.refresh(matrixId)

            val firstTestStatus = refreshedMatrix.testExecutions.first()

            val details: TestDetails? = firstTestStatus.testDetails
            if (details != null) {
                error = details.errorMessage
                if (error != null) {
                    puts("Error: $error")
                }
                progress = details.progressMessages ?: progress
            }

            // if the matrix is already completed, return after checking for error.
            if (MatrixState.completed(refreshedMatrix.state)) {
                break
            }

            // Progress contains all messages. only print new updates
            for (msg in progress.listIterator(lastProgressLen)) {
                puts(msg)
            }
            lastProgressLen = progress.size

            if (firstTestStatus.state != lastState) {
                lastState = firstTestStatus.state
                puts(lastState)
            }

            Utils.sleep(6)
        }

        return refreshedMatrix
    }

    fun runReports(matrixMap: MatrixMap) {
        CostSummary.run(matrixMap)
        ResultSummary.run(matrixMap)
    }

    // used to update results from an async run
    suspend fun refreshLastRun() {
        val matrixMap = lastMatrices()

        refreshMatrices(matrixMap)
        runReports(matrixMap)
        fetchArtifacts(matrixMap)
    }

    suspend fun newRun(config: YamlConfig) {
        println(config)
        val matrixMap = runTests(config)

        if (config.waitForResults) {
            pollMatrices(matrixMap)
            fetchArtifacts(matrixMap)
        }
    }
}
