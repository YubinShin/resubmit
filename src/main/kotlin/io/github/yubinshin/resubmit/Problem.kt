package io.github.yubinshin.resubmit

internal data class Problem(
    val id: String,
    val title: String,
    val description: String,
    val submissionContract: String,
    val solutionTemplate: String,
    val testCases: List<ProblemTestCase>,
    val relatedSkills: List<String>,
    val wrongAnswerHints: List<String>,
    val optimizationHints: List<String>,
    val source: ProblemSource? = null,
)

internal data class ProblemSource(
    val repository: String,
    val path: String,
    val recordId: String,
    val createdAt: String,
)

internal data class ProblemTestCase(
    val id: String,
    val invocation: String,
    val expected: String,
    val legacyArguments: String? = null,
    val arrayResult: Boolean = false,
)

internal fun interface ProblemSelector {
    fun select(problems: List<Problem>): Problem?
}

internal object ProblemCatalog {
    val targetNumber = Problem(
        id = "target-number-demo",
        title = "부호 선택 합계",
        description = "배열의 각 값 앞에 더하기 또는 빼기 기호를 붙여 식을 만든다. 식의 값이 target인 부호 배치 수를 구한다. 원소 수는 1~12개이고 각 값은 1~20이다.",
        submissionContract = "public class Solution에 solution(int[] numbers, int target) 메서드를 구현하세요.",
        solutionTemplate = """
            public class Solution {
                public int solution(int[] numbers, int target) {
                    // TODO: 구현하세요.
                    return 0;
                }
            }
        """.trimIndent() + System.lineSeparator(),
        testCases = listOf(
            targetNumberCase("case-1", listOf(2, 3), 1, 1),
            targetNumberCase("case-2", listOf(2, 2), 0, 2),
            targetNumberCase("case-3", listOf(3), 3, 1),
            targetNumberCase("case-4", listOf(3), 2, 0),
        ),
        relatedSkills = listOf("재귀", "상태 탐색"),
        wrongAnswerHints = listOf(
            "각 숫자에서 더하기와 빼기 두 선택이 모두 다음 상태로 이어지는지 확인해보세요.",
            "두 재귀 호출에 전달하는 현재 합의 연산이 서로 다른지 코드에서 직접 비교해보세요.",
            "각 단계의 결과를 dfs(index + 1, sum + number)와 dfs(index + 1, sum - number)의 합으로 구성해보세요.",
        ),
        optimizationHints = listOf(
            "통과한 풀이의 시간 복잡도와 재귀 깊이가 입력 크기에서 안전한지 확인해보세요.",
            "각 depth에서 두 분기가 생길 때 전체 호출 수가 입력 길이에 따라 어떻게 증가하는지 계산해보세요.",
            "필요하다면 (depth, sum)별 결과를 저장하는 방식으로 중복 탐색을 줄일 수 있는지 검토해보세요.",
        ),
    )

    val pairSumCount = Problem(
        id = "pair-sum-count-demo",
        title = "인덱스 쌍 합계",
        description = "길이 0~20인 정수 배열에서 위치 i와 j를 i < j가 되도록 고른다. 두 위치의 값 합이 target인 선택의 개수를 센다. 같은 값이라도 위치가 다르면 다른 선택이며 값의 범위는 -20~20이다.",
        submissionContract = "public class Solution에 solution(int[] numbers, int target) 메서드를 구현하세요. 각 인덱스 쌍은 한 번만 셉니다.",
        solutionTemplate = targetNumber.solutionTemplate,
        testCases = listOf(
            pairSumCase("case-1", listOf(2, 2, 2), 4, 3),
            pairSumCase("case-2", listOf(3, 8, 1, 6), 9, 2),
            pairSumCase("case-3", listOf(-1, 2, 4), 3, 1),
            pairSumCase("case-4", listOf(2, 5), 9, 0),
        ),
        relatedSkills = listOf("해시맵", "조합 수 계산"),
        wrongAnswerHints = listOf(
            "같은 값이 여러 번 있을 때 서로 다른 인덱스의 쌍을 어떻게 세는지 확인해보세요.",
            "각 원소를 볼 때 필요한 짝의 값이 앞에서 몇 번 나왔는지 확인해보세요.",
            "앞에서 확인한 값의 빈도를 저장하고, 현재 값과 target의 차이에 해당하는 빈도를 누적해보세요.",
        ),
        optimizationHints = listOf(
            "현재 풀이가 모든 인덱스 쌍을 비교하는지 확인하고 입력이 커질 때 연산 횟수를 계산해보세요.",
            "각 원소를 한 번씩 처리하면서 필요한 보완값의 빈도를 누적할 수 있는지 살펴보세요.",
            "빈도 정보를 HashMap에 저장하면 한 번의 순회로 셀 수 있는지 검토해보세요.",
        ),
    )

    val twoSum = Problem(
        id = "demo-pair-indices",
        title = "목표합 인덱스",
        description = "길이 2~20인 배열에서 두 위치의 값 합이 target인 유일한 쌍을 찾는다. 작은 위치와 큰 위치를 순서대로 담은 배열을 반환한다. 한 위치를 두 번 고르지 않으며 각 값은 -20~20이다.",
        submissionContract = "public class Solution에 solution(int[] nums, int target) 메서드를 구현하세요.",
        solutionTemplate = """
            public class Solution {
                public int[] solution(int[] nums, int target) {
                    // TODO: 구현하세요.
                    return new int[0];
                }
            }
        """.trimIndent() + System.lineSeparator(),
        testCases = listOf(
            intArrayCase("case-1", "new Solution().solution(new int[] {4, 9, 6}, 10)", "[0, 2]"),
            intArrayCase("case-2", "new Solution().solution(new int[] {8, 3, 5}, 11)", "[0, 1]"),
            intArrayCase("case-3", "new Solution().solution(new int[] {5, 5}, 10)", "[0, 1]"),
        ),
        relatedSkills = listOf("해시"),
        wrongAnswerHints = listOf(
            "현재 원소 하나만으로 답을 만들 수 있는지보다, 합을 완성하는 짝이 앞에서 나왔는지 확인해보세요.",
            "각 원소를 볼 때 target에서 그 값을 뺀 숫자를 이전 원소에서 찾을 수 있는지 살펴보세요.",
            "필요한 보완값과 그 인덱스를 저장하고, 짝을 찾으면 두 인덱스를 반환해보세요.",
        ),
        optimizationHints = listOf(
            "모든 인덱스 쌍을 검사할 때 입력 크기에 따라 연산 횟수가 어떻게 늘어나는지 계산해보세요.",
            "이전에 본 값의 인덱스를 저장해 한 번의 순회로 해결할 수 있는지 검토해보세요.",
            "HashMap으로 보완값을 조회하면 시간 복잡도를 O(n)으로 낮출 수 있습니다.",
        ),
        source = ProblemSource("resubmit", "src/main/kotlin/io/github/yubinshin/resubmit/Problem.kt", "demo-pair-indices", "2026-10-27"),
    )

    val validParentheses = Problem(
        id = "demo-bracket-order",
        title = "닫힘 순서 확인",
        description = "소괄호·대괄호·중괄호로만 된 길이 0~40의 문자열을 읽는다. 닫는 기호는 가장 최근에 열려서 아직 닫히지 않은 같은 종류의 기호와 짝을 이뤄야 한다. 끝까지 모든 짝이 맞으면 true를 반환하며 빈 문자열도 true다.",
        submissionContract = "public class Solution에 solution(String s) 메서드를 구현하세요.",
        solutionTemplate = """
            public class Solution {
                public boolean solution(String s) {
                    // TODO: 구현하세요.
                    return false;
                }
            }
        """.trimIndent() + System.lineSeparator(),
        testCases = listOf(
            scalarCase("case-1", "new Solution().solution(\"{[()]}\")", "true"),
            scalarCase("case-2", "new Solution().solution(\"{{}\")", "false"),
            scalarCase("case-3", "new Solution().solution(\"[{}()]\")", "true"),
            scalarCase("case-4", "new Solution().solution(\"]{\")", "false"),
            scalarCase("case-5", "new Solution().solution(\"[{]}\")", "false"),
        ),
        relatedSkills = listOf("스택"),
        wrongAnswerHints = listOf(
            "닫는 괄호를 만났을 때 앞에서 열린 괄호와 종류와 순서가 맞는지 확인해보세요.",
            "아직 닫히지 않은 괄호를 입력 순서대로 추적하려면 어떤 자료구조가 맞을까요?",
            "여는 괄호는 쌓고, 닫는 괄호마다 가장 최근 여는 괄호를 꺼내 짝을 비교해보세요.",
        ),
        optimizationHints = listOf(
            "입력의 각 문자를 몇 번씩 처리하는지 세어보세요.",
            "각 문자를 한 번씩 읽으며 여는 괄호만 저장하면 충분한지 살펴보세요.",
            "스택으로 한 번 순회하면 O(n) 시간에 판정할 수 있습니다.",
        ),
        source = ProblemSource("resubmit", "src/main/kotlin/io/github/yubinshin/resubmit/Problem.kt", "demo-bracket-order", "2026-10-27"),
    )

    val searchInsertPosition = Problem(
        id = "demo-insert-slot",
        title = "정렬 목록의 자리",
        description = "서로 다른 정수가 작은 순서로 놓인 길이 0~20의 배열이 있다. target 앞에는 target보다 작은 값만 오도록 놓을 위치를 구한다. 이미 같은 값이 있으면 그 위치를, 없다면 삽입할 위치를 0부터 세어 반환한다.",
        submissionContract = "public class Solution에 solution(int[] nums, int target) 메서드를 구현하세요.",
        solutionTemplate = """
            public class Solution {
                public int solution(int[] nums, int target) {
                    // TODO: 구현하세요.
                    return 0;
                }
            }
        """.trimIndent() + System.lineSeparator(),
        testCases = listOf(
            scalarCase("case-1", "new Solution().solution(new int[] {2, 6, 9}, 6)", "1"),
            scalarCase("case-2", "new Solution().solution(new int[] {2, 6, 9}, 7)", "2"),
            scalarCase("case-3", "new Solution().solution(new int[] {3, 8}, 1)", "0"),
            scalarCase("case-4", "new Solution().solution(new int[] {}, 4)", "0"),
        ),
        relatedSkills = listOf("이분탐색"),
        wrongAnswerHints = listOf(
            "target이 배열의 양 끝보다 작거나 클 때 반환해야 할 위치를 먼저 확인해보세요.",
            "탐색 구간을 절반으로 줄이면서 target이 들어갈 경계를 찾을 수 있을까요?",
            "왼쪽 경계를 갱신하는 이분 탐색으로 target 이상의 첫 위치를 찾아보세요.",
        ),
        optimizationHints = listOf(
            "배열을 순서대로 훑을 때와 절반씩 줄여 찾을 때의 비교 횟수를 생각해보세요.",
            "정렬된 배열이라는 조건을 이용해 탐색 범위를 줄여보세요.",
            "Lower bound 형태의 이분 탐색으로 O(log n)에 찾을 수 있습니다.",
        ),
        source = ProblemSource("resubmit", "src/main/kotlin/io/github/yubinshin/resubmit/Problem.kt", "demo-insert-slot", "2026-10-27"),
    )

    val numberOfIslands = Problem(
        id = "demo-land-groups",
        title = "격자 묶음 세기",
        description = "1~8행, 1~8열의 직사각형 문자 격자에서 0은 빈칸, 1은 표시된 칸이다. 가로나 세로로 닿은 표시 칸을 이어서 만든 묶음의 수를 구한다. 대각선으로만 닿은 칸은 다른 묶음이다.",
        submissionContract = "public class Solution에 solution(char[][] grid) 메서드를 구현하세요.",
        solutionTemplate = """
            public class Solution {
                public int solution(char[][] grid) {
                    // TODO: 구현하세요.
                    return 0;
                }
            }
        """.trimIndent() + System.lineSeparator(),
        testCases = listOf(
            scalarCase("case-1", "new Solution().solution(new char[][] {{'1','1','0'},{'0','1','0'},{'0','0','1'}})", "2"),
            scalarCase("case-2", "new Solution().solution(new char[][] {{'1','0','1'},{'0','0','0'},{'1','0','1'}})", "4"),
            scalarCase("case-3", "new Solution().solution(new char[][] {{'1','1','1'},{'1','1','1'}})", "1"),
            scalarCase("case-4", "new Solution().solution(new char[][] {{'0','0','0'}})", "0"),
        ),
        relatedSkills = listOf("BFS", "DFS", "연결 요소"),
        wrongAnswerHints = listOf(
            "각 땅 칸에서 상하좌우로 이어지는 땅을 모두 같은 섬으로 묶어보세요.",
            "아직 방문하지 않은 땅을 찾으면 그 칸에서 탐색을 시작해 한 덩어리를 표시할 수 있습니다.",
            "격자를 순회하며 미방문 땅마다 BFS나 DFS를 한 번 실행하고 섬 개수를 늘려보세요.",
        ),
        optimizationHints = listOf(
            "같은 땅 칸을 여러 번 방문하지 않도록 표시하는 방법을 생각해보세요.",
            "각 칸을 최대 한 번 방문하면 전체 작업량은 격자 칸 수에 비례합니다.",
            "방문 표시를 함께 하는 BFS나 DFS는 O(rows × columns) 시간에 동작합니다.",
        ),
        source = ProblemSource("resubmit", "src/main/kotlin/io/github/yubinshin/resubmit/Problem.kt", "demo-land-groups", "2026-10-27"),
    )

    val all: List<Problem> = listOf(targetNumber, pairSumCount, twoSum, validParentheses, searchInsertPosition, numberOfIslands)

    private fun targetNumberCase(id: String, numbers: List<Int>, target: Int, expected: Int): ProblemTestCase {
        val array = numbers.joinToString(", ")
        return ProblemTestCase(
            id = id,
            invocation = "new Solution().solution(new int[] {$array}, $target)",
            expected = expected.toString(),
            legacyArguments = "new int[] {$array}, $target, $expected",
        )
    }

    private fun pairSumCase(id: String, numbers: List<Int>, target: Int, expected: Int): ProblemTestCase {
        val array = numbers.joinToString(", ")
        return ProblemTestCase(
            id = id,
            invocation = "new Solution().solution(new int[] {$array}, $target)",
            expected = expected.toString(),
        )
    }

    private fun scalarCase(id: String, invocation: String, expected: String): ProblemTestCase =
        ProblemTestCase(id = id, invocation = invocation, expected = expected)

    private fun intArrayCase(id: String, invocation: String, expected: String): ProblemTestCase =
        ProblemTestCase(id = id, invocation = invocation, expected = expected, arrayResult = true)
}

// Preserve source compatibility for existing tests and callers while production uses the catalog.
internal object DemoProblem {
    val targetNumber: Problem = ProblemCatalog.targetNumber
}
