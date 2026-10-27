# 로컬 검증 기록

- 실행일: 2026-10-27 (Asia/Seoul)
- 기준 HEAD: `51df546dc6cf62ccf7fd7fdf74c74476db08b4d2`
- 작업 트리: 골든셋 JSON, ReviewEvaluationTest, 평가 문서와 이 기록을 추가한 미커밋 상태. 기존 bin/은 미추적이며 빌드 후 .kotlin/도 미추적으로 표시됨.
- 환경: macOS 로컬, Temurin OpenJDK 21.0.11+10-LTS, 저장소 Gradle Wrapper 사용.
- 명령: `./gradlew build --rerun-tasks`
- 원본 콘솔 결과: `BUILD SUCCESSFUL in 23s`, `9 actionable tasks: 9 executed`, exit code 0.
- 기존 MainTest.kt에서 불필요한 변환 호출에 관한 컴파일 경고가 발생했으며 빌드는 통과함.

테스트 결과의 원본은 `build/test-results/test/TEST-*.xml`이다. 이 파일들은 빌드 산출물이며 Git 추적 대상이 아니다. 재현 시 위 명령으로 새로 생성한다.

XML 22개 루트의 tests/failures/errors/skipped 속성을 합산한 결과는 tests=142, failures=0, errors=0, skipped=0이다. 이 중 CliWorkflowE2eTest는 6개 테스트, ReviewEvaluationTest는 4개 사례를 순회하는 1개 테스트이며 모두 통과했다.

골든셋 검증은 직접 작성한 Java 제출을 실제 javac/java로 실행해 사례별 기대 컴파일 상태와 통과 개수를 비교한다. 기존 CLI E2E의 리뷰는 fixture다. 이 실행에서는 외부 LLM을 호출하지 않았으며 설명의 의미적 정확성, 정답 조기 노출 또는 실제 API 계약의 유효성을 검증했다고 해석하지 않는다.

## 실제 LLM 평가 이후 추가 검증

사용자 승인 후 Gemini에 네 사례를 각 1회 호출했다. 결과와 측정 조건은 [평가 기록](../evaluation.md)에 별도로 기록했다. 위의 무호출 검증 기록과 구분한다.

실제 응답을 고정 자료로 보관하고 production Gemini 파서·스키마·근거 ID 검증에 통과하는지 오프라인 재생 테스트를 추가했다. 요청 미리보기 검사도 포함하여 `./gradlew build --rerun-tasks`를 실행한 결과 `BUILD SUCCESSFUL in 26s`, `9 actionable tasks: 9 executed`, exit code 0이었다. 이 빌드 과정에서 추가 API 호출은 없었다. 기존 MainTest.kt 컴파일 경고는 남아 있다.

해당 실행의 JUnit XML 22개 합산은 tests=144, failures=0, errors=0, skipped=0이다. ReviewEvaluationTest의 3개 테스트는 각각 골든 실행 라벨, 무호출 요청 미리보기, 실제 응답의 오프라인 재생을 검사한다. 설명 품질 판정은 이 자동 테스트 수와 별개로 평가 기록에 남겼다.

## review-v4 재평가 이후 검증

기준 코드 `2f47210`에서 사용자 승인 후 실제 API를 최대 4회 호출한 원본을 추가하고, 기존 응답 재생 테스트가 v3·v4 자료를 모두 확인하도록 확장했다. `./gradlew build --rerun-tasks`의 원본 결과는 `BUILD SUCCESSFUL in 20s`, `9 actionable tasks: 9 executed`, exit code 0이다. JUnit XML 합산은 tests=144, failures=0, errors=0, skipped=0이며 기존 재생 테스트 안에서 두 버전의 응답을 검사하므로 테스트 개수는 증가하지 않았다. 빌드는 추가 API를 호출하지 않았고 기존 MainTest.kt 컴파일 경고는 남아 있다. 실제 설명 품질의 비교와 토큰 증가는 평가 기록에 별도로 남겼다.

## 8개 사례 반복 평가 이후 검증

cases.json을 버전 2의 8개 사례로 확장하고 버전 1 원본을 cases-v1.json에 보관했다. API 호출 전에 실제 javac/java로 컴파일 상태·통과 개수·예상 반환값을 검사한 전체 빌드는 `BUILD SUCCESSFUL in 25s`, exit code 0이었다. 최초 sandbox 실행은 Gradle 캐시 잠금 파일 접근 권한으로 실패하여, 권한을 허용받아 같은 명령을 재실행한 결과다.

사용자가 승인한 48회 호출이 끝난 후 원본 응답을 고정 자료로 추가했다. 기존 재생 테스트 안에서 과거 8개 응답과 이번 48개 응답을 production 파서·스키마·근거 ID 검증에 통과시키도록 확장했다. 이후 `./gradlew build --rerun-tasks`는 `BUILD SUCCESSFUL in 14s`, `9 actionable tasks: 9 executed`, exit code 0이었다. JUnit XML 22개 합산은 tests=144, failures=0, errors=0, skipped=0이며 ReviewEvaluationTest는 3개 테스트 메서드 안에서 여러 사례와 응답을 순회한다. 48개의 새 테스트 메서드를 추가한 것은 아니다.

빌드 과정에서 API는 호출하지 않았으며 기존 MainTest.kt 컴파일 경고는 남아 있다. 실행 도구는 Python ast 파싱과 무호출 계획 출력으로 확인했고, 기록된 데이터·도구의 해시와 실제 파일이 같은지 대조했다. 원본 JSON의 키·인증 헤더·로컬 사용자 경로 패턴 검사에도 일치 항목이 없었다. 의미적 품질 판정과 한계는 [평가 기록](../evaluation.md)에 별도로 보관한다.

## 배포용 CLI fixture 데모

반복 평가 자료를 `9c58380`으로 커밋한 후 `./gradlew installDist`로 실행 파일을 생성했다. 첫 sandbox 시도는 Gradle 캐시 접근 제한으로 실패했으며 권한 허용 후 `BUILD SUCCESSFUL in 454ms`를 확인했다. `python3 tools/run_fixture_demo.py`는 첫 sandbox 실행에서 하위 Java 프로세스의 sysctl 접근 제한으로 RUNNER_ERROR를 기록했고, 권한 허용 후 재실행했다.

최종 데모는 미리 작성한 두 재귀 분기가 모두 더하기인 코드에서 한 분기를 빼기로 고치는 시나리오다. 배포용 CLI를 임시 작업 폴더에서 실행하여 exitCode=0, 제출 2회, 고정 테스트 1/4에서 4/4 통과, 힌트 1·2·3단계 공개와 이력을 확인했다. provider는 fixture이고 API 호출은 없었다. 사용자 프로필은 가상 데이터이며 코드 교체도 스크립트가 수행했다. [데모 문서](../demo.md)와 경로를 치환한 실행 출력을 보관한다.

데모 도구와 문서 추가 후 `./gradlew build --rerun-tasks`의 원본 결과는 `BUILD SUCCESSFUL in 13s`, `9 actionable tasks: 9 executed`, exit code 0이었다. JUnit XML 22개 합산은 tests=144, failures=0, errors=0, skipped=0이었다. 이후 README는 사용자에게 기존 문장 교체 목록과 후보본을 제시해 승인받아 적용했다. 문서 링크와 기존 두 text 도식이 그대로인지 확인했다. 대화형 화면 갱신·GUI 에디터 조작·실제 API에 연결한 CLI 전체 데모는 이번 검증에 포함하지 않는다.

## 제출용 공개 스냅샷 검증

로컬 기준 75e284c의 추적 파일을 별도 폴더에 복사하고, 승인한 번들 CSV 제외·내장 6개 fixture의 설명과 예제 교체·선택적 로컬 카탈로그 전환을 적용했다. 원본 main의 파일과 Git 이력은 변경하지 않았다. 기존 실제 LLM 평가 JSON 및 평가 도구는 기준 스냅샷과 바이트 단위로 같음을 확인했다.

첫 빌드는 153개 테스트 중 기존 code-rpg 출처 문자열을 기대하던 SessionWorkspaceTest 1개가 실패했다. 공개 fixture의 실제 resubmit 출처를 검사하도록 수정했다. 이후 `./gradlew build installDist --rerun-tasks` 결과는 `BUILD SUCCESSFUL in 24s`, `10 actionable tasks: 10 executed`, exit code 0이었다. JUnit XML 23개 합산은 tests=153, failures=0, errors=0, skipped=0이었다. 기존 MainTest.kt 불필요 변환 경고는 남아 있다.

새 입력·예상값을 실제 javac/java로 확인했고, 배포용 CLI fixture 데모도 exitCode=0, 제출 2회, 테스트 1/4→4/4, 힌트 3단계 기록을 확인했다. 실행 출력은 공개 fixture로 새로 생성했다. 앱 JAR에 catalog/problems.csv가 없고, 기본 화면에는 내장 6문제만 표시됨을 확인했다. 원래 CSV의 30자 이상 설명 전체 문자열이 후보의 읽을 수 있는 소스·문서에 남았는지 검사해 일치 항목이 없었다. 이 문자열 검사는 모든 권리 문제의 부재를 입증하는 법적 검토가 아니다.

개인 환경 파일·학습 이력·bin·원본 Git backup은 복사하지 않았다. README의 두 text 도식은 기준 스냅샷과 같고 문서 링크도 확인했다. 추가 LLM 호출이나 원격 게시·원격 CI 실행은 하지 않았다. 과거 실험 commit SHA와 새 공개 이력의 관계는 공개본 구성 기록에 설명한다.
