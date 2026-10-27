package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.system.exitProcess

private val DIAGNOSTIC_PROBLEM_IDS = listOf("demo-pair-indices", "demo-bracket-order", "demo-insert-slot", "demo-land-groups")

internal fun DiagnosticOutcome.label(): String = when (this) {
    DiagnosticOutcome.PASSED -> "통과"
    DiagnosticOutcome.NOT_PASSED -> "미통과"
    DiagnosticOutcome.COMPILE_FAILED -> "컴파일 실패"
    DiagnosticOutcome.TIMED_OUT -> "시간 초과"
    DiagnosticOutcome.NOT_SUBMITTED -> "미제출"
}

private fun formatDuration(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

fun main() {
    val environment = System.getenv()
    val historyFile = Path.of(".resubmit/history/attempts.jsonl").toAbsolutePath().normalize()
    val output = PrintWriter(System.out, true)
    val detectedTerminalWidth = if (
        environment["TERM"] != null && environment["TERM"] != "dumb" && environment["CI"] == null
    ) {
        TerminalWidthDetector.detect()
    } else {
        null
    }
    val hasSystemConsole = System.console() != null
    val terminalUi = TerminalUi.fromEnvironment(
        environment,
        hasSystemConsole,
        detectedWidth = detectedTerminalWidth,
    )
    val diagnosticSessionStore = JsonDiagnosticSessionStore(
        Path.of(".resubmit/diagnostic/session.json").toAbsolutePath().normalize(),
    )
    val restoreScreen = Thread({
        runCatching { checkpointActiveDiagnosticSession(diagnosticSessionStore, Clock.systemDefaultZone().millis()) }
        terminalUi.leaveScreen(output)
    }, "terminal-screen-restore")
    Runtime.getRuntime().addShutdownHook(restoreScreen)
    // Gradle's `run` task and some IDE consoles expose a pipe as stdin even
    // when TERM is set. JLine can render there, but raw key input is unavailable.
    val jlineInput = if (terminalUi.supportsInteractiveInput && hasSystemConsole) {
        JLineInput.createOrNull()
    } else {
        null
    }
    val input = jlineInput ?: System.`in`.bufferedReader()
    val exitCode = try {
        CodingCoachCli(
            input = input,
            output = output,
            workspaceFactory = SessionWorkspaceFactory(
                projectRoot = Path.of(".").toAbsolutePath().normalize(),
                clock = Clock.systemDefaultZone(),
            ),
            codeRunner = ProcessJavaCodeRunner(),
            testRunner = ProcessJavaTestRunner(),
            snapshotFactory = SubmissionSnapshotFactory(Clock.systemDefaultZone()),
            reviewProvider = ReviewProviderFactory.create(environment),
            attemptHistoryReader = JsonlAttemptHistoryReader(historyFile),
            attemptRecorder = JsonlAttemptRecorder(historyFile),
            editorLauncher = EnvironmentEditorLauncher(environment),
            terminalUi = terminalUi,
            problemSelector = InteractiveProblemSelector(input, output, terminalUi),
            selfReportStore = JsonSelfReportStore(
                Path.of(".resubmit/profile.json").toAbsolutePath().normalize(),
            ),
            selfReportProvider = InteractiveSelfReportProvider(input, output, terminalUi = terminalUi),
            diagnosticSessionStore = diagnosticSessionStore,
            repositoryAnalyzer = RepositoryCodeAnalyzer(
                environment["RESUBMIT_ANALYSIS_ROOT"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: Path.of("coding-test").toAbsolutePath().normalize(),
                Path.of(".resubmit/source-analysis.json").toAbsolutePath().normalize(),
            ),
            learningCatalog = environment["RESUBMIT_CODE_RPG_HOME"]?.takeIf(String::isNotBlank)
                ?.let { LocalLearningCatalog(Path.of(it)) } ?: LocalLearningCatalog(),
        ).run()
    } finally {
        runCatching { jlineInput?.close() }
        runCatching { Runtime.getRuntime().removeShutdownHook(restoreScreen) }
    }

    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

internal class CodingCoachCli(
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val workspaceFactory: SessionWorkspaceFactory,
    private val codeRunner: CodeRunner,
    private val testRunner: TestRunner,
    private val snapshotFactory: SnapshotFactory,
    private val reviewProvider: ReviewProvider,
    private val attemptHistoryReader: AttemptHistoryReader,
    private val attemptRecorder: AttemptRecorder,
    private val editorLauncher: EditorLauncher = EnvironmentEditorLauncher(emptyMap()),
    private val terminalUi: TerminalUi = TerminalUi.plain(),
    private val diagnosticRenderer: CompilerDiagnosticRenderer = CompilerDiagnosticRenderer(),
    private val problemSelector: ProblemSelector = ProblemSelector { problems -> problems.firstOrNull() },
    private val selfReportStore: SelfReportStore? = null,
    private val selfReportProvider: SelfReportProvider? = null,
    private val diagnosticSessionStore: DiagnosticSessionStore? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val learningCatalog: LearningCatalog = LocalLearningCatalog(),
    private val repositoryAnalyzer: RepositoryCodeAnalyzer? = null,
) {
    private var notices: List<String> = emptyList()
    private val reportedHistoryAttempts = mutableSetOf<String>()

    fun run(): Int {
        terminalUi.enterScreen(output)
        return try {
            runSession()
        } finally {
            terminalUi.leaveScreen(output)
        }
    }

    private fun runSession(): Int {
        if (!ensureSelfReport()) return SUCCESS
        if (selfReportStore != null && selfReportProvider != null) {
            return runHomeMenu()
        }
        return (runProblemSession() as? SessionFlow.Exit)?.exitCode ?: SUCCESS
    }

    private fun runHomeMenu(): Int {
        val options = buildList {
            add("학습 시작")
            if (diagnosticSessionStore != null) add("초기 진단")
            add("내 프로필")
            add("도움말")
            add("종료")
        }
        while (true) {
            val profileDescription = when (val saved = selfReportStore?.read()) {
                is SelfReportReadResult.Found -> "현재 자기보고: ${saved.profile.programmingLanguage} · ${saved.profile.codingExperience.label()} · 프로필 수정"
                else -> "초기 진단 전에 자기보고를 작성하면 유형별 경험을 진단 결과와 함께 비교할 수 있습니다. 프로필 수정에서 입력합니다."
            }
            val selected = ChoiceMenu(input, output, terminalUi).select(
                title = "Re:Submit",
                description = profileDescription,
                options = options,
            ) ?: return SUCCESS
            when (selected) {
                0 -> when (val flow = runLearningSession()) {
                    SessionFlow.Menu -> Unit
                    is SessionFlow.Exit -> return flow.exitCode
                    else -> error("문제 선택 흐름이 종료되지 않았습니다: $flow")
                }
                1 -> if (diagnosticSessionStore != null) {
                    when (val flow = runDiagnosticSession()) {
                        SessionFlow.Menu -> Unit
                        is SessionFlow.Exit -> return flow.exitCode
                        else -> error("진단 흐름이 종료되지 않았습니다: $flow")
                    }
                } else {
                    val flow = runProfileMenu()
                    if (flow is SessionFlow.Exit) return flow.exitCode
                }
                2 -> if (diagnosticSessionStore != null) {
                    val flow = runProfileMenu()
                    if (flow is SessionFlow.Exit) return flow.exitCode
                } else showHelp()
                3 -> if (diagnosticSessionStore != null) showHelp() else return SUCCESS
                4 -> return SUCCESS
            }
        }
    }

    private fun runLearningSession(): SessionFlow {
        while (true) {
            val catalog = learningCatalog.load()
            val insights = buildLearningInsights(catalog.entries, attemptHistoryReader.allRecords(), diagnosticSessionStore?.read())
            val resumable = catalog.entries.mapNotNull { it.executable }.filter { problem ->
                workspaceFactory.existing(problem).any { workspace ->
                    when (val latest = attemptHistoryReader.latest(workspace.sessionId)) {
                        HistoryReadResult.NotFound -> true
                        is HistoryReadResult.Found -> latest.attempt.tests.status != TestStatus.PASSED
                        is HistoryReadResult.Failed -> false
                    }
                }
            }.map { it.id }.toSet()
            val selected = LearningScreens(input, output, terminalUi, clock)
                .selectProblem(catalog, insights, resumable, repositoryAnalyzer?.read()) ?: return SessionFlow.Menu
            when (val flow = runSelectedProblem(selected)) {
                SessionFlow.NextProblem -> Unit
                else -> return flow
            }
        }
    }

    private fun runProfileMenu(): SessionFlow {
        var repository = repositoryAnalyzer?.read()
        while (true) {
            val catalog = learningCatalog.load()
            val diagnostic = diagnosticSessionStore?.read()
            val insights = buildLearningInsights(catalog.entries, attemptHistoryReader.allRecords(), diagnostic)
            when (LearningScreens(input, output, terminalUi, clock).profile(catalog, insights, selfReportStore?.read(), diagnostic, repository)) {
                ProfileAction.ANALYZE -> {
                    output.println("저장소의 Java 코드를 분석합니다.")
                    output.flush()
                    repository = runCatching { repositoryAnalyzer?.analyze() }.getOrElse {
                        RepositoryAnalysis("", "", emptyList(), listOf("분석 실패: ${it.message}"), stale = true)
                    } ?: RepositoryAnalysis("", "", emptyList(), listOf("분석 디렉터리가 설정되지 않았습니다."), stale = true)
                }
                ProfileAction.BACK -> return SessionFlow.Menu
                ProfileAction.EDIT -> if (!updateSelfReport()) return SessionFlow.Menu
                ProfileAction.LEARN -> when (val flow = runLearningSession()) {
                    is SessionFlow.Exit -> return flow
                    else -> Unit
                }
            }
        }
    }

    private fun runDiagnosticSession(): SessionFlow {
        val store = diagnosticSessionStore ?: return SessionFlow.Menu
        val problems = DIAGNOSTIC_PROBLEM_IDS.mapNotNull { id -> ProblemCatalog.all.firstOrNull { it.id == id } }
        if (problems.size != DIAGNOSTIC_PROBLEM_IDS.size) {
            output.println("진단 문제 구성을 불러오지 못했습니다.")
            return SessionFlow.Menu
        }

        var session = when (val saved = store.read()) {
            DiagnosticSessionReadResult.NotFound -> newDiagnosticSession(problems)
            is DiagnosticSessionReadResult.Failed -> {
                output.println("진단 세션을 불러오지 못했습니다: ${saved.message}")
                return SessionFlow.Menu
            }
            is DiagnosticSessionReadResult.Found -> if (saved.session.status == DiagnosticStatus.COMPLETED) {
                newDiagnosticSession(problems)
            } else {
                val record = saved.session
                if (record.problemIds != problems.map(Problem::id) || record.currentProblemIndex !in 0..problems.size) {
                    output.println("저장된 진단 구성이 현재 문제 목록과 달라 이어서 진행할 수 없습니다.")
                    return SessionFlow.Menu
                }
                record.copy(status = DiagnosticStatus.PAUSED, activeSinceEpochMillis = null)
            }
        }

        if (session.status == DiagnosticStatus.PAUSED && session.currentProblemIndex > 0) {
            output.println("중단된 진단을 이어서 진행합니다. 저장된 제출 결과와 누적 시간을 불러왔습니다.")
        }
        if (session.status == DiagnosticStatus.PAUSED && session.currentProblemIndex == 0 && session.observations.isNotEmpty()) {
            output.println("중단된 진단을 이어서 진행합니다.")
        }

        while (session.currentProblemIndex < problems.size) {
            val problem = problems[session.currentProblemIndex]
            val knownSessionId = session.problemSessionIds[problem.id]
            val knownWorkspace = knownSessionId?.let { id ->
                workspaceFactory.existing(problem).firstOrNull { it.sessionId == id }
            }
            val workspace = knownWorkspace ?: workspaceFactory.create(problem)
            val startedAt = clock.millis()
            session = session.copy(
                status = DiagnosticStatus.IN_PROGRESS,
                activeSinceEpochMillis = startedAt,
                problemSessionIds = session.problemSessionIds + (problem.id to workspace.sessionId),
            )
            if (!storeDiagnosticSession(store, session)) return SessionFlow.Menu

            val progress = "진단 ${session.currentProblemIndex + 1}/${problems.size} · 누적 풀이 ${formatDuration(session.elapsedSeconds)}"
            val flow = runSelectedProblem(
                problem = problem,
                forcedWorkspace = workspace,
                diagnosticProgress = progress,
                resumedWorkspace = knownWorkspace != null,
            )
            val elapsed = ((clock.millis() - startedAt).coerceAtLeast(0) / 1_000)
            val priorObservation = session.observations.firstOrNull { it.problemId == problem.id }
            val observation = observeDiagnosticProblem(
                problem.id, workspace.sessionId, priorObservation, session.currentProblemElapsedSeconds + elapsed,
            )
            val solved = observation.outcome == DiagnosticOutcome.PASSED
            val shouldAdvance = solved
            val nextIndex = if (shouldAdvance) session.currentProblemIndex + 1 else session.currentProblemIndex
            val completed = nextIndex >= problems.size
            session = session.copy(
                currentProblemIndex = nextIndex,
                status = if (completed) DiagnosticStatus.COMPLETED else DiagnosticStatus.PAUSED,
                elapsedSeconds = session.elapsedSeconds + elapsed,
                currentProblemElapsedSeconds = 0,
                activeSinceEpochMillis = null,
                observations = (session.observations.filterNot { it.problemId == problem.id } + observation),
            )
            if (!storeDiagnosticSession(store, session)) return SessionFlow.Menu

            if (completed) {
                showDiagnosticSummary(session, problems)
                return if (flow is SessionFlow.Exit) flow else SessionFlow.Menu
            }
            when (flow) {
                SessionFlow.NextProblem -> Unit
                SessionFlow.Menu -> return SessionFlow.Menu
                is SessionFlow.Exit -> return flow
                else -> error("진단 문제 흐름이 종료되지 않았습니다: $flow")
            }
        }

        session = session.copy(status = DiagnosticStatus.COMPLETED, activeSinceEpochMillis = null)
        if (!storeDiagnosticSession(store, session)) return SessionFlow.Menu
        showDiagnosticSummary(session, problems)
        return SessionFlow.Menu
    }

    private fun newDiagnosticSession(problems: List<Problem>): DiagnosticSessionRecord = DiagnosticSessionRecord(
        sessionId = java.util.UUID.randomUUID().toString(),
        problemIds = problems.map(Problem::id),
        status = DiagnosticStatus.PAUSED,
    ).also {
        output.println("새 초기 진단을 시작합니다. 네 문제를 순서대로 풉니다.")
        output.println("문제 풀이 시간은 진단 화면에 머문 시간으로 기록됩니다.")
    }

    private fun storeDiagnosticSession(store: DiagnosticSessionStore, session: DiagnosticSessionRecord): Boolean =
        when (val result = store.write(session)) {
            DiagnosticSessionWriteResult.Success -> true
            is DiagnosticSessionWriteResult.Failed -> {
                output.println("진단 진행 상황을 저장하지 못했습니다: ${result.message}")
                false
            }
        }

    private fun observeDiagnosticProblem(
        problemId: String,
        sessionId: String,
        previous: DiagnosticObservation?,
        elapsedSeconds: Long,
    ): DiagnosticObservation {
        val attempts = when (val result = attemptHistoryReader.all(sessionId)) {
            is HistoryRecordsReadResult.Found -> result.attempts
            HistoryRecordsReadResult.NotFound -> emptyList()
            is HistoryRecordsReadResult.Failed -> {
                output.println("제출 이력을 불러오지 못해 진단 근거가 일부 제한됩니다: ${result.message}")
                emptyList()
            }
        }
        val latest = attempts.lastOrNull()
        val outcome = when {
            latest == null -> DiagnosticOutcome.NOT_SUBMITTED
            latest.compilation.status == CompilationStatus.TIMED_OUT || latest.tests.status == TestStatus.TIMED_OUT ->
                DiagnosticOutcome.TIMED_OUT
            latest.compilation.status == CompilationStatus.FAILED -> DiagnosticOutcome.COMPILE_FAILED
            latest.tests.status == TestStatus.PASSED -> DiagnosticOutcome.PASSED
            else -> DiagnosticOutcome.NOT_PASSED
        }
        return DiagnosticObservation(
            problemId = problemId,
            outcome = outcome,
            attemptCount = attempts.map(AttemptRecord::attemptId).distinct().size,
            hintLevelViewed = (attempts.mapNotNull { it.review.hintLevel } + listOfNotNull(previous?.hintLevelViewed)).maxOrNull(),
            elapsedSeconds = (previous?.elapsedSeconds ?: 0) + elapsedSeconds,
        )
    }

    private fun showDiagnosticSummary(session: DiagnosticSessionRecord, problems: List<Problem>) {
        terminalUi.clearScreen(output)
        output.println(terminalUi.bold("초기 진단 결과"))
        output.println("누적 풀이 시간: ${formatDuration(session.elapsedSeconds)}")
        output.println()
        val claimed = (selfReportStore?.read() as? SelfReportReadResult.Found)?.profile
        val missingClaims = session.observations.flatMap { observation ->
            problems.firstOrNull { it.id == observation.problemId }?.relatedSkills.orEmpty()
        }.distinct().filterNot { skill -> claimed?.claimedSkillExperience?.containsKey(skill) == true }
        if (missingClaims.isNotEmpty()) {
            output.println("자기보고가 입력되지 않은 유형: ${missingClaims.joinToString(", ")}")
            output.println("‘내 프로필 → 학습 목표·자기보고 수정’에서 보완할 수 있습니다.")
            output.println()
        }
        session.observations.sortedBy { observation ->
            problems.indexOfFirst { it.id == observation.problemId }
        }.forEach { observation ->
            val problem = problems.first { it.id == observation.problemId }
            output.println("${problem.title} · ${problem.relatedSkills.joinToString(", ")}")
            output.println(
                "  관찰: ${observation.outcome.label()} · 제출 ${observation.attemptCount}회 · " +
                    "힌트 ${observation.hintLevelViewed?.let { "${it}단계까지" } ?: "없음"} · ${formatDuration(observation.elapsedSeconds)}",
            )
            problem.relatedSkills.forEach { skill ->
                val claim = claimed?.claimedSkillExperience?.get(skill)?.label() ?: "입력 없음"
                output.println("  $skill 사전 경험: $claim")
            }
            output.println()
        }
        val firstNeedsPractice = session.observations.firstOrNull { it.outcome != DiagnosticOutcome.PASSED }
        if (firstNeedsPractice == null && session.observations.size == problems.size) {
            output.println("다음 학습 제안")
            output.println("네 문제를 모두 통과했습니다. 다른 문제도 풀어 관찰 근거를 넓혀보세요.")
        } else if (firstNeedsPractice != null) {
            val problem = problems.first { it.id == firstNeedsPractice.problemId }
            output.println("다음 학습 제안")
            output.println("${problem.relatedSkills.joinToString(", ")} 유형을 다시 풀어보세요. 근거: ${firstNeedsPractice.outcome.label()}.")
        } else {
            output.println("아직 확인하지 않은 진단 문제가 있습니다. 진단을 이어서 진행하세요.")
        }
        output.println()
        output.println("[Enter] 메뉴")
        output.flush()
        input.readLine()
    }

    private fun runProblemSession(): SessionFlow {
        while (true) {
            terminalUi.clearScreen(output)
            val problem = problemSelector.select(ProblemCatalog.all) ?: return SessionFlow.Exit(SUCCESS)
            when (val flow = runSelectedProblem(problem)) {
                SessionFlow.NextProblem -> Unit
                SessionFlow.Menu -> return flow
                is SessionFlow.Exit -> return flow
                else -> error("문제 풀이 흐름이 종료되지 않았습니다: $flow")
            }
        }
    }

    private fun runSelectedProblem(
        problem: Problem,
        forcedWorkspace: SessionWorkspace? = null,
        diagnosticProgress: String? = null,
        resumedWorkspace: Boolean = false,
    ): SessionFlow {
        terminalUi.clearScreen(output)
        output.println(terminalUi.bold("Re:Submit / ${problem.title}"))
        diagnosticProgress?.let { output.println(it) }
        output.println()
        printProblem(problem)
        val selection = if (forcedWorkspace != null) {
            WorkspaceSelection(
                workspace = forcedWorkspace,
                resumed = resumedWorkspace,
                hasAttempts = attemptHistoryReader.latest(forcedWorkspace.sessionId) is HistoryReadResult.Found,
            )
        } else {
            selectWorkspace(problem)
        } ?: return SessionFlow.Exit(SUCCESS)
        val workspace = selection.workspace
        terminalUi.clearScreen(output)
        output.println(terminalUi.bold(if (selection.resumed) "기존 작업 파일을 이어서 사용합니다." else "작업 파일을 만들었습니다."))
        output.println(terminalUi.pathLine("문제", workspace.problemFile, indent = "  "))
        output.println(terminalUi.pathLine("제출", workspace.solutionFile, indent = "  "))
        output.println()
        var firstSubmission = !selection.hasAttempts
        val stateMachine = SubmissionStateMachine()
        var completionFlow: SessionFlow? = null
        while (stateMachine.state != SubmissionState.FINISHED) {
            val submissionCommand = awaitSubmissionCommand(workspace, firstSubmission, problem)
            when (submissionCommand) {
                is SessionFlow.Exit -> stateMachine.transition(
                    SubmissionEvent.EXIT,
                    submissionCommand.exitCode,
                )
                SessionFlow.Edit -> openEditor(workspace)
                SessionFlow.NextProblem, SessionFlow.Menu -> error("문제 흐름 명령은 제출 입력에서 올 수 없습니다.")
                SessionFlow.Submit -> {
                    stateMachine.transition(SubmissionEvent.SUBMIT)
                    when (val result = submit(workspace, stateMachine, problem)) {
                        SessionFlow.Edit -> {
                            stateMachine.transition(SubmissionEvent.EDIT)
                            firstSubmission = false
                            openEditor(workspace)
                        }
                        is SessionFlow.Exit -> stateMachine.transition(
                            if (result.exitCode == SUCCESS) SubmissionEvent.EXIT else SubmissionEvent.FAILURE,
                            result.exitCode,
                        )
                        SessionFlow.NextProblem,
                        SessionFlow.Menu -> {
                            completionFlow = result
                            stateMachine.transition(SubmissionEvent.EXIT, SUCCESS)
                        }
                        SessionFlow.Submit -> error("제출 결과가 다시 제출 상태일 수 없습니다.")
                    }
                }
            }
        }
        return completionFlow ?: SessionFlow.Exit(requireNotNull(stateMachine.exitCode))
    }

    private fun updateSelfReport(): Boolean {
        val store = selfReportStore ?: return false
        val provider = selfReportProvider ?: return false
        val skills = ProblemCatalog.all.flatMap(Problem::relatedSkills).distinct()
        val profile = provider.collect(skills) ?: return false
        return when (val result = store.write(profile)) {
            SelfReportWriteResult.Success -> true
            is SelfReportWriteResult.Failed -> {
                output.println("학습 프로필을 저장하지 못했습니다: ${result.message}")
                false
            }
        }
    }

    private fun showHelp() {
        terminalUi.clearScreen(output)
        output.println(terminalUi.bold("도움말"))
        output.println()
        output.println("학습 시작: 추천, 복습, 유형별 연습, 검색으로 문제를 찾습니다.")
        if (diagnosticSessionStore != null) output.println("초기 진단: 고정된 네 문제를 풀고 결과를 저장합니다.")
        output.println("내 프로필: 유형별 관찰과 진단 근거를 확인하고 학습 목표·자기보고를 수정합니다.")
        output.println("저장소 코드 분석: 기본 coding-test, RESUBMIT_ANALYSIS_ROOT로 디렉터리를 지정합니다.")
        output.println("문제 화면: e 편집, Enter 제출, h 힌트, q 저장 후 종료")
        output.println()
        output.println("[Enter] 돌아가기")
        output.flush()
        input.readLine()
    }

    private fun ensureSelfReport(): Boolean {
        val store = selfReportStore ?: return true
        val provider = selfReportProvider ?: return true
        return when (val saved = store.read()) {
            is SelfReportReadResult.Found -> true
            SelfReportReadResult.NotFound -> {
                val skills = ProblemCatalog.all.flatMap(Problem::relatedSkills).distinct()
                val profile = provider.collect(skills) ?: return false
                when (val write = store.write(profile)) {
                    SelfReportWriteResult.Success -> true
                    is SelfReportWriteResult.Failed -> {
                        output.println("학습 프로필을 저장하지 못했습니다: ${write.message}")
                        false
                    }
                }
            }
            is SelfReportReadResult.Failed -> {
                output.println("저장된 학습 프로필을 읽지 못했습니다: ${saved.message}")
                false
            }
        }
    }

    private fun selectWorkspace(problem: Problem): WorkspaceSelection? {
        val resumable = workspaceFactory.existing(problem).firstNotNullOfOrNull { workspace ->
            when (val history = attemptHistoryReader.latest(workspace.sessionId)) {
                is HistoryReadResult.Found -> if (history.attempt.completed()) {
                    null
                } else {
                    WorkspaceSelection(workspace, resumed = true, hasAttempts = true)
                }
                HistoryReadResult.NotFound -> WorkspaceSelection(workspace, resumed = true, hasAttempts = false)
                is HistoryReadResult.Failed -> {
                    output.println("세션 이력을 불러오지 못했습니다: ${history.message}")
                    WorkspaceSelection(workspace, resumed = true, hasAttempts = true)
                }
            }
        } ?: return WorkspaceSelection(workspaceFactory.create(problem), resumed = false, hasAttempts = false)

        var redraw = terminalUi.clearScreen(output)
        while (true) {
            if (redraw) {
                printHeader(problem)
                redraw = false
            }
            output.println(terminalUi.bold("중단된 세션"))
            output.println(resumable.workspace.sessionId)
            output.println()
            output.println("[r] 이어서 풀기  [n] 새로 시작  [q] 종료")
            output.print("> ")
            output.flush()
            when (input.readLine()?.trim()?.lowercase()) {
                "r", "ㄱ" -> return resumable
                "n", "ㅜ" -> return WorkspaceSelection(
                    workspaceFactory.create(problem),
                    resumed = false,
                    hasAttempts = false,
                )
                "q", "ㅂ", null -> return null
                else -> output.println("입력을 이해하지 못했습니다. r(재개), n(새 문제) 또는 q(종료)를 입력해주세요.")
            }
        }
    }

    private fun awaitSubmissionCommand(workspace: SessionWorkspace, firstSubmission: Boolean, problem: Problem): SessionFlow {
        val heading = if (firstSubmission) "제출 준비" else "재제출 준비"
        val prompt = if (firstSubmission) {
            "[e] 편집  [Enter] 제출  [q] 종료"
        } else {
            "[e] 편집  [Enter] 재제출  [q] 종료"
        }
        if (terminalUi.clearScreen(output)) {
            printHeader(problem)
            output.println(terminalUi.muted("PROBLEM"))
            terminalUi.wrap(problem.description, indent = "  ").forEach(output::println)
            printTestExamples(problem)
            output.println()
        }
        var actionsPrinted = false
        while (true) {
            if (!actionsPrinted) {
                output.println(terminalUi.muted("WORKSPACE"))
                output.println(terminalUi.bold(heading))
                if (notices.isNotEmpty()) {
                    output.println()
                    notices.forEach(output::println)
                    notices = emptyList()
                }
                output.println()
                printActions(prompt)
                actionsPrinted = true
            }
            when (input.readLine()?.trim()?.lowercase()) {
                "" -> return SessionFlow.Submit
                "e", "ㄷ" -> return SessionFlow.Edit
                "q", "ㅂ" -> {
                    printWorkspacePreserved()
                    return SessionFlow.Exit(SUCCESS)
                }
                null -> {
                    output.println("입력이 종료되었습니다.")
                    printWorkspacePreserved()
                    return SessionFlow.Exit(SUCCESS)
                }
                else -> {
                    output.println("입력을 이해하지 못했습니다. Enter(제출), e(편집) 또는 q(종료)를 입력해주세요.")
                    printPrompt()
                }
            }
        }
    }

    private fun submit(workspace: SessionWorkspace, stateMachine: SubmissionStateMachine, problem: Problem): SessionFlow {
        if (terminalUi.clearScreen(output)) printHeader(problem)
        val snapshot = try {
            snapshotFactory.create(workspace)
        } catch (exception: Exception) {
            output.println("제출 스냅샷 생성 실패: ${exception.message ?: exception::class.simpleName.orEmpty()}")
            return SessionFlow.Exit(INFRASTRUCTURE_ERROR)
        }
        val sourceFile = snapshot.sourceFile.toAbsolutePath().normalize()
        output.println(terminalUi.muted("ATTEMPT ${snapshot.attemptNumber()}"))
        output.println(terminalUi.bold("실행 결과"))
        output.println()
        val classDirectory = snapshot.sourceFile.parent.resolve("classes")
        val compilation = codeRunner.compile(listOf(sourceFile, workspace.harnessFile), classDirectory)
        val tests = when (compilation) {
            is CompilationResult.Success -> {
                output.println(terminalUi.statusRow("컴파일", "PASS", "${compilation.durationMs} ms", TerminalTone.SUCCESS))
                runTests(compilation.outputDirectory, problem)
            }
            is CompilationResult.Failed -> {
                output.println(
                    terminalUi.statusRow(
                        "컴파일",
                        "FAIL",
                        "exit ${compilation.exitCode} · ${compilation.durationMs} ms",
                        TerminalTone.FAILURE,
                    ),
                )
                output.println()
                output.print(diagnosticRenderer.render(sourceFile, compilation.diagnostics))
                if (compilation.outputTruncated) {
                    output.println("[javac 출력이 제한을 초과해 일부만 분석했습니다.]")
                }
                output.flush()
                null
            }
            is CompilationResult.TimedOut -> {
                output.println(
                    terminalUi.statusRow("컴파일", "TIMEOUT", "${compilation.durationMs} ms", TerminalTone.WARNING),
                )
                printDiagnostics(compilation.diagnostics, compilation.outputTruncated)
                null
            }
            is CompilationResult.RunnerError -> {
                output.println(terminalUi.statusRow("컴파일", "RUNNER_ERROR", tone = TerminalTone.FAILURE))
                output.println(compilation.message)
                null
            }
        }
        val executionFailed = compilation is CompilationResult.RunnerError ||
            tests is TestRunResult.ExecutionError || tests is TestRunResult.RunnerError
        output.println()
        if (executionFailed) {
            recordAttempt(workspace, snapshot, compilation, tests, ReviewHistory.NotRun, problem)
            return SessionFlow.Exit(INFRASTRUCTURE_ERROR)
        }
        printAttemptComparison(workspace, snapshot, compilation, tests)
        if (!recordAttempt(workspace, snapshot, compilation, tests, ReviewHistory.NotRun, problem)) {
            return SessionFlow.Exit(INFRASTRUCTURE_ERROR)
        }
        if (tests == null && compilation !is CompilationResult.Failed) {
            stateMachine.transition(SubmissionEvent.EVALUATION_COMPLETED)
            return presentEditOrExit()
        }
        val reviewResult = loadReview(compilation, tests, workspace, snapshot, problem)
        val initialReviewHistory = reviewResult.toHistory(hintViewed = false)
        if (!recordAttempt(workspace, snapshot, compilation, tests, initialReviewHistory, problem)) {
            return SessionFlow.Exit(INFRASTRUCTURE_ERROR)
        }
        stateMachine.transition(SubmissionEvent.EVALUATION_COMPLETED)
        val reviewPresentation = presentReview(
            reviewResult,
            problem,
            persistHintView = { history ->
                recordAttempt(workspace, snapshot, compilation, tests, history, problem)
            },
            isSolved = compilation is CompilationResult.Success &&
                tests is TestRunResult.Completed && tests.passedCount == tests.cases.size,
        )
        if (!reviewPresentation.historyWriteAttempted && reviewPresentation.history != initialReviewHistory &&
            !recordAttempt(workspace, snapshot, compilation, tests, reviewPresentation.history, problem)
        ) {
            return SessionFlow.Exit(INFRASTRUCTURE_ERROR)
        }
        return reviewPresentation.flow
    }

    private fun printAttemptComparison(
        workspace: SessionWorkspace,
        snapshot: SubmissionSnapshot,
        compilation: CompilationResult,
        tests: TestRunResult?,
    ) {
        when (val result = attemptHistoryReader.latest(workspace.sessionId)) {
            HistoryReadResult.NotFound -> Unit
            is HistoryReadResult.Failed -> output.println("재제출 비교를 불러오지 못했습니다: ${result.message}")
            is HistoryReadResult.Found -> {
                val previous = result.attempt
                output.println()
                output.println(terminalUi.muted("ATTEMPT ${snapshot.attemptNumber()} 비교"))
                output.println(terminalUi.statusRow("테스트", "${previous.tests.summary()} → ${tests.summary()}"))
                output.println(terminalUi.statusRow("결과", "${previous.resultStatus()} → ${compilation.resultStatus(tests)}"))
                output.println(terminalUi.statusRow("힌트", previous.review.hintUsage()))
                val sourceChange = if (previous.sourceSha256 == snapshot.sourceSha256) "동일" else "변경 확인"
                output.println(terminalUi.statusRow("변경", "source hash $sourceChange"))
                output.println()
            }
        }
    }

    private fun loadReview(
        compilation: CompilationResult,
        tests: TestRunResult?,
        workspace: SessionWorkspace,
        snapshot: SubmissionSnapshot,
        problem: Problem,
    ): ReviewResult {
        val previousAttempt = when (
            val history = attemptHistoryReader.previous(workspace.sessionId, snapshot.attemptId)
        ) {
            is HistoryReadResult.Found -> PreviousAttemptContext(
                record = history.attempt,
                sourceCode = previousSubmissionFile(workspace, history.attempt.attemptId)
                    ?.let { path -> runCatching { Files.readString(path) }.getOrNull() },
            )
            HistoryReadResult.NotFound,
            is HistoryReadResult.Failed -> null
        }
        val sourceFile = snapshot.sourceFile
        val sourceCode = Files.readString(sourceFile)
        val request = ReviewRequest(
            problem = problem,
            tests = tests,
            sourceCode = sourceCode,
            compilation = compilation,
            previousAttempt = previousAttempt,
            sourceEvidence = SourceEvidenceAnalyzer().analyze(sourceCode, snapshot.sourceSha256),
        )
        return try {
            if (reviewProvider is StructuredLlmReviewProvider) {
                terminalUi.withProgress(output, "${reviewProvider.provider} 리뷰 요청 중") {
                    reviewProvider.review(request)
                }
            } else {
                reviewProvider.review(request)
            }
        } catch (exception: Exception) {
            ReviewResult.Unavailable(exception.message ?: exception::class.simpleName.orEmpty())
        }
    }

    private fun previousSubmissionFile(workspace: SessionWorkspace, attemptId: String): Path? {
        val prefix = "${workspace.sessionId}-attempt-"
        if (!attemptId.startsWith(prefix)) return null
        val number = attemptId.removePrefix(prefix).toIntOrNull()?.takeIf { it > 0 } ?: return null
        return workspace.directory.resolve(
            ".internal/submissions/attempt-${number.toString().padStart(3, '0')}/Solution.java",
        ).takeIf(Files::isRegularFile)
    }

    private fun presentReview(
        result: ReviewResult,
        problem: Problem,
        persistHintView: (ReviewHistory.Ready) -> Boolean,
        isSolved: Boolean,
    ): ReviewPresentation {
        return when (result) {
            is ReviewResult.Unavailable -> {
                output.println()
                output.println(terminalUi.statusRow("리뷰", "UNAVAILABLE", tone = TerminalTone.WARNING))
                output.println(result.message)
                val history = ReviewHistory.Unavailable(reviewProvider.provider, result.message)
                if (isSolved) presentSolvedActions(history) else ReviewPresentation(presentEditOrExit(), history)
            }
            is ReviewResult.Ready -> presentReadyReview(result.review, problem, persistHintView, isSolved)
        }
    }

    private fun ReviewResult.toHistory(hintViewed: Boolean): ReviewHistory = when (this) {
        is ReviewResult.Unavailable -> ReviewHistory.Unavailable(reviewProvider.provider, message)
        is ReviewResult.Ready -> ReviewHistory.Ready(review, hintViewed)
    }

    private fun presentReadyReview(
        review: SubmissionReview,
        problem: Problem,
        persistHintView: (ReviewHistory.Ready) -> Boolean,
        isSolved: Boolean,
    ): ReviewPresentation {
        output.println()
        output.println(terminalUi.statusRow("리뷰", "READY", review.provider, TerminalTone.SUCCESS))
        output.println(terminalUi.statusRow("분류", review.failureType.label()))
        output.println()
        output.println(terminalUi.muted("검토 근거"))
        review.evidence.forEach { evidence -> output.println("  $evidence") }
        if (isSolved) {
            return presentSolvedActions(ReviewHistory.Ready(review, hintViewed = false))
        }
        var hintsShown = 0
        var hintViewPersisted = false
        var actionsPrinted = false
        while (true) {
            if (!actionsPrinted) {
                if (hintsShown == review.hints.size) {
                    printActions("[e] 다시 편집  [q] 종료")
                } else {
                    printActions("[h] ${hintsShown + 1}단계 힌트  [e] 다시 편집  [q] 종료")
                }
                actionsPrinted = true
            }
            when (input.readLine()?.trim()?.lowercase()) {
                "h", "ㅗ" -> if (hintsShown == review.hints.size) {
                    output.println("현재 제공할 수 있는 힌트는 모두 확인했습니다.")
                } else {
                    val hintLevel = hintsShown + 1
                    if (terminalUi.clearScreen(output)) printHeader(problem)
                    output.println(terminalUi.muted("HINT $hintLevel/${review.hints.size}"))
                    output.println(terminalUi.bold(hintLabel(hintLevel)))
                    output.println()
                    output.println(review.hints[hintsShown])
                    hintsShown = hintLevel
                    actionsPrinted = false
                    val history = ReviewHistory.Ready(
                        review,
                        hintViewed = true,
                        hintLevelViewed = hintLevel,
                    )
                    hintViewPersisted = true
                    if (!persistHintView(history)) {
                        return ReviewPresentation(
                            SessionFlow.Exit(INFRASTRUCTURE_ERROR),
                            history,
                            historyWriteAttempted = true,
                        )
                    }
                }
                "e", "ㄷ" -> return ReviewPresentation(
                    SessionFlow.Edit,
                    ReviewHistory.Ready(review, hintsShown > 0, hintsShown.takeIf { it > 0 }),
                    historyWriteAttempted = hintViewPersisted,
                )
                "q", "ㅂ", null -> return ReviewPresentation(
                    SessionFlow.Exit(SUCCESS),
                    ReviewHistory.Ready(review, hintsShown > 0, hintsShown.takeIf { it > 0 }),
                    historyWriteAttempted = hintViewPersisted,
                )
                else -> {
                    output.println("입력을 이해하지 못했습니다. h(힌트), e(편집) 또는 q(종료)를 입력해주세요.")
                    printPrompt()
                }
            }
        }
    }

    private fun presentSolvedActions(history: ReviewHistory): ReviewPresentation {
        printActions("[Enter] 다음 문제  [m] 메뉴  [q] 종료")
        while (true) {
            when (input.readLine()?.trim()?.lowercase()) {
                "" -> return ReviewPresentation(SessionFlow.NextProblem, history)
                "m", "ㅁ" -> return ReviewPresentation(SessionFlow.Menu, history)
                "q", "ㅂ", null -> return ReviewPresentation(SessionFlow.Exit(SUCCESS), history)
                else -> {
                    output.println("입력을 이해하지 못했습니다. Enter(다음 문제), m(메뉴) 또는 q(종료)를 입력해주세요.")
                    printPrompt()
                }
            }
        }
    }

    private fun presentEditOrExit(): SessionFlow {
        var actionsPrinted = false
        while (true) {
            if (!actionsPrinted) {
                printActions("[e] 다시 편집  [q] 종료")
                actionsPrinted = true
            }
            when (input.readLine()?.trim()?.lowercase()) {
                "e", "ㄷ" -> return SessionFlow.Edit
                "q", "ㅂ", null -> return SessionFlow.Exit(SUCCESS)
                else -> {
                    output.println("입력을 이해하지 못했습니다. e(편집) 또는 q(종료)를 입력해주세요.")
                    printPrompt()
                }
            }
        }
    }

    private fun runTests(classDirectory: Path, problem: Problem): TestRunResult {
        val result = testRunner.run(classDirectory, problem.testCases)
        when (result) {
            is TestRunResult.Completed -> {
                val tone = if (result.passedCount == result.cases.size) {
                    TerminalTone.SUCCESS
                } else {
                    TerminalTone.WARNING
                }
                output.println(
                    terminalUi.statusRow(
                        "테스트",
                        "${result.passedCount} / ${result.cases.size} PASS",
                        "${result.durationMs} ms",
                        tone,
                    ),
                )
                val failedCases = result.cases.filterNot(TestCaseResult::passed)
                if (failedCases.isNotEmpty()) {
                    output.println()
                    output.println(terminalUi.muted("실패한 테스트"))
                }
                failedCases.forEach { testCase ->
                    when (testCase) {
                        is TestCaseResult.Value -> output.println(
                            "  ${testCase.id}  expected ${testCase.expected}, actual ${testCase.actual}",
                        )
                        is TestCaseResult.Error -> output.println("  ${testCase.id}  ${testCase.errorType}")
                    }
                }
                printTestOutput(result.output, result.outputTruncated)
            }
            is TestRunResult.TimedOut -> {
                output.println(
                    terminalUi.statusRow("테스트", "TIMEOUT", "${result.durationMs} ms", TerminalTone.WARNING),
                )
                printTestOutput(result.output, result.outputTruncated)
            }
            is TestRunResult.ExecutionError -> {
                output.println(
                    terminalUi.statusRow(
                        "테스트",
                        "EXECUTION_ERROR",
                        "exit ${result.exitCode} · ${result.durationMs} ms",
                        TerminalTone.FAILURE,
                    ),
                )
                output.println(result.message)
                printTestOutput(result.output, result.outputTruncated)
            }
            is TestRunResult.RunnerError -> {
                output.println(terminalUi.statusRow("테스트", "RUNNER_ERROR", tone = TerminalTone.FAILURE))
                output.println(result.message)
            }
        }
        return result
    }

    private fun recordAttempt(
        workspace: SessionWorkspace,
        snapshot: SubmissionSnapshot,
        compilation: CompilationResult,
        tests: TestRunResult?,
        review: ReviewHistory,
        problem: Problem,
    ): Boolean {
        return when (val result = attemptRecorder.record(
            workspace,
            snapshot,
            problem,
            compilation,
            tests,
            review,
        )) {
            is HistoryWriteResult.Success -> {
                if (reportedHistoryAttempts.add(snapshot.attemptId)) {
                    output.println(terminalUi.muted("이력 저장됨"))
                }
                true
            }
            is HistoryWriteResult.Failed -> {
                output.println("이력 저장 실패: ${result.message}")
                false
            }
        }
    }

    private fun printDiagnostics(diagnostics: String, truncated: Boolean) {
        if (diagnostics.isNotBlank()) {
            output.print(diagnostics)
            if (!diagnostics.endsWith(System.lineSeparator())) {
                output.println()
            }
            output.flush()
        }
        if (truncated) {
            output.println("[출력이 제한을 초과해 일부만 표시됩니다.]")
        }
    }

    private fun printWorkspacePreserved() {
        output.println("작업 파일을 유지하고 종료합니다.")
    }

    private fun openEditor(workspace: SessionWorkspace) {
        val solutionFile = workspace.solutionFile.toAbsolutePath().normalize()
        terminalUi.leaveScreen(output)
        when (val result = editorLauncher.open(solutionFile)) {
            EditorLaunchResult.NotConfigured -> {
                notices = listOf(
                    "에디터가 설정되지 않았습니다. RESUBMIT_EDITOR 또는 EDITOR를 설정할 수 있습니다.",
                    "수동 편집: $solutionFile",
                )
            }
            is EditorLaunchResult.Completed -> if (result.exitCode == 0) {
                notices = listOf("에디터가 종료되었습니다. 제출 대기 화면으로 돌아갑니다.")
            } else {
                notices = listOf(
                    "에디터 실행 실패: 종료 코드 ${result.exitCode}",
                    "수동 편집: $solutionFile",
                )
            }
            is EditorLaunchResult.Failed -> {
                notices = listOf(
                    "에디터 실행 실패: ${result.message}",
                    "수동 편집: $solutionFile",
                )
            }
        }
        terminalUi.enterScreen(output)
    }

    private fun printProblem(problem: Problem) {
        output.println("문제      ${problem.id}")
        output.println(terminalUi.bold("설명"))
        terminalUi.wrap(problem.description, indent = "  ").forEach(output::println)
        output.println(terminalUi.bold("제출 규약"))
        terminalUi.wrap(problem.submissionContract, indent = "  ").forEach(output::println)
        printTestExamples(problem)
    }

    private fun printTestExamples(problem: Problem) {
        output.println(terminalUi.bold("공개 테스트 예시"))
        problem.testCases.forEachIndexed { index, testCase ->
            output.println("  ${index + 1}. 입력:")
            terminalUi.wrap(testCase.invocation, indent = "     ").forEach(output::println)
            output.println("     기대 결과: ${testCase.expected}")
        }
    }

    private fun printTestOutput(value: String, truncated: Boolean) {
        if (value.isBlank() && !truncated) return
        output.println()
        output.println(terminalUi.muted("제출 로그 (stdout / stderr)"))
        if (value.isNotBlank()) printDiagnostics(value, truncated)
        else if (truncated) output.println("[출력이 제한을 초과해 일부만 수집했습니다.]")
    }

    private fun printHeader(problem: Problem) {
        output.println(terminalUi.bold("Re:Submit / ${problem.title}"))
        output.println()
    }

    private fun printActions(commands: String) {
        output.println()
        output.println(commands)
        printPrompt()
    }

    private fun printPrompt() {
        output.print("› ")
        output.flush()
    }

    private companion object {
        const val SUCCESS = 0
        const val INFRASTRUCTURE_ERROR = 3
    }
}

private fun ReviewFailureType.label(): String = when (this) {
    ReviewFailureType.COMPILE_ERROR -> "컴파일 오류"
    ReviewFailureType.WRONG_ANSWER -> "오답"
    ReviewFailureType.RUNTIME_ERROR -> "실행 오류"
    ReviewFailureType.TIME_LIMIT_EXCEEDED -> "시간 초과"
    ReviewFailureType.PASSED -> "통과"
}

private fun SubmissionSnapshot.attemptNumber(): Int = attemptId.substringAfterLast("attempt-").toIntOrNull() ?: 0

private fun TestRecord.summary(): String = if (passedCount != null && totalCount != null) {
    "$passedCount/$totalCount"
} else {
    status.name
}

private fun TestRunResult?.summary(): String = when (this) {
    is TestRunResult.Completed -> "$passedCount/${cases.size}"
    is TestRunResult.TimedOut -> "TIMEOUT"
    is TestRunResult.ExecutionError -> "EXECUTION_ERROR"
    is TestRunResult.RunnerError -> "RUNNER_ERROR"
    null -> "NOT_RUN"
}

private fun AttemptRecord.resultStatus(): String = when (compilation.status) {
    CompilationStatus.FAILED -> "COMPILE_FAILED"
    CompilationStatus.TIMED_OUT -> "COMPILE_TIMED_OUT"
    CompilationStatus.RUNNER_ERROR -> "RUNNER_ERROR"
    CompilationStatus.PASSED -> tests.status.name
}

private fun AttemptRecord.completed(): Boolean =
    compilation.status == CompilationStatus.PASSED && tests.status == TestStatus.PASSED

private fun CompilationResult.resultStatus(tests: TestRunResult?): String = when (this) {
    is CompilationResult.Failed -> "COMPILE_FAILED"
    is CompilationResult.TimedOut -> "COMPILE_TIMED_OUT"
    is CompilationResult.RunnerError -> "RUNNER_ERROR"
    is CompilationResult.Success -> when (tests) {
        is TestRunResult.Completed -> if (tests.passedCount == tests.cases.size) "PASSED" else "FAILED"
        is TestRunResult.TimedOut -> "TIMED_OUT"
        is TestRunResult.ExecutionError -> "EXECUTION_ERROR"
        is TestRunResult.RunnerError -> "RUNNER_ERROR"
        null -> "NOT_RUN"
    }
}

private fun ReviewRecord.hintUsage(): String = if (hintViewed && hintLevel != null) {
    "${hintLevel}단계 사용"
} else {
    "사용 안 함"
}

private fun hintLabel(level: Int): String = when (level) {
    1 -> "방향만 제시"
    2 -> "코드의 문제 지점"
    3 -> "의사코드와 개선 방향"
    else -> error("지원하지 않는 힌트 단계입니다: $level")
}

internal sealed interface SessionFlow {
    data object Submit : SessionFlow
    data object Edit : SessionFlow
    data object NextProblem : SessionFlow
    data object Menu : SessionFlow
    data class Exit(val exitCode: Int) : SessionFlow
}

internal data class ReviewPresentation(
    val flow: SessionFlow,
    val history: ReviewHistory,
    val historyWriteAttempted: Boolean = false,
)

internal data class WorkspaceSelection(
    val workspace: SessionWorkspace,
    val resumed: Boolean,
    val hasAttempts: Boolean,
)

internal class InteractiveProblemSelector(
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val terminalUi: TerminalUi = TerminalUi.plain(),
) : ProblemSelector {
    override fun select(problems: List<Problem>): Problem? {
        val choices = problems.map { problem -> "${problem.title} · ${problem.relatedSkills.joinToString(", ")}" }
        val selected = ChoiceMenu(input, output, terminalUi).select(
            "문제 선택",
            "풀 문제를 선택해주세요.",
            choices,
        ) ?: return null
        return problems[selected]
    }
}

internal sealed interface ReviewHistory {
    data object NotRun : ReviewHistory
    data class Unavailable(val provider: String, val message: String) : ReviewHistory
    data class Ready(
        val review: SubmissionReview,
        val hintViewed: Boolean,
        val hintLevelViewed: Int? = if (hintViewed) 1 else null,
    ) : ReviewHistory {
        init {
            require(hintViewed == (hintLevelViewed != null)) { "힌트 열람 여부와 단계가 일치해야 합니다." }
            require(hintLevelViewed == null || hintLevelViewed in 1..review.hints.size) {
                "힌트 열람 단계가 리뷰 범위를 벗어났습니다."
            }
        }
    }
}

internal fun applicationBanner(): String = "Re:Submit — Evidence-based Coding Test Coach"
