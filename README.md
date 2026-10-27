# Adaptive Coding Coach

코딩 테스트 제출을 직접 실행하고, 실행 결과를 근거로 단계적 힌트를 제공하는 개인용 학습 도구다.

현재 저장소는 Kotlin/JVM CLI에서 제출을 실행하고 fixture 또는 선택한 외부 LLM으로 리뷰하는 초기 workflow를 구현한다. LLM 리뷰와 평가 방법을 학습하기 위한 개인 프로젝트이며, 상용 서비스 운영 경험을 나타내지 않는다.

## 검토와 재현

이 공개본은 검증한 로컬 프로젝트에서 제출용으로 분리한 스냅샷이다. 이전 실험 커밋과 공개 이력의 관계는 [공개본 구성 기록](docs/publication.md)에 설명한다.

JDK 21이 필요하다. Gradle Wrapper를 사용하며 첫 실행에는 배포본·의존성 다운로드가 필요할 수 있다.

```bash
./gradlew build --rerun-tasks
RESUBMIT_REVIEW_PROVIDER=fixture ./gradlew -q run --console=plain
```

- [실제 CLI 데모](docs/demo.md): API 없이 제출 → 힌트 3단계 → 수정 → 재제출을 실행한다. 자동 데모에는 Python 3.9 이상이 필요하다.
- [LLM 품질 평가](docs/evaluation.md): Gemini 한 모델에서 고정 8개 사례를 버전별 3회씩 평가한 원본·기준·한계를 보관한다.
- [빌드·테스트 검증 기록](docs/validation/submission-check.md): 실행 명령, 환경과 결과를 기록한다. 자동 테스트 통과와 LLM 설명 품질 판정은 별개다.

현재 코드의 실행 판정·이력·힌트 흐름은 로컬 CLI 범위다. Tool-calling, Multi-Agent, 서버 API, 원격 코드 실행 격리와 상용 운영 안정성은 구현·검증한 범위에 포함하지 않는다.

## 목표

첫 번째 실행 가능한 흐름은 다음과 같다.

```text
Kotlin CLI 실행
  → 문제 한 개 표시
  → Solution.java 템플릿 생성·편집 후 제출
  → javac 컴파일
  → 고정 테스트 실행
  → 결정론적 결과 생성
  → 결과에 근거한 구조화된 리뷰와 단계적 힌트 출력
  → 제출·평가 이력 저장
```

정오답, 컴파일 오류, 시간 초과는 코드 실행 결과로 판정한다. LLM은 판정을 변경하지 않고 실패 원인 설명과 단계적 힌트 생성에만 사용한다.

## 현재 상태

### 작성됨

- 프로젝트 목표와 첫 실행 흐름
- 초기 아키텍처 결정 기록(ADR)
- Kotlin/JVM 21 단일 Gradle 프로젝트와 Gradle Wrapper
- 문제 표시와 세션별 Java 제출 템플릿 생성 CLI
- 실제 `javac` 컴파일과 성공·오류·timeout 결과 구분
- 고정 테스트 실행과 정답·오답·실행 timeout 구분
- javac 위치·코드·caret·help를 표시하는 구조화된 컴파일 진단
- session/attempt, 실행 결과, source SHA-256, 리뷰·힌트 사용 여부를 남기는 JSONL 이력
- 같은 세션의 직전 JSONL 이력과 현재 재제출 결과 비교
- 제출 대기, 평가, 사용자 행동 대기와 종료 전이를 검증하는 제출 상태 머신
- 실행 결과 기반 fixture 리뷰와 사용자가 요청할 때 순서대로 공개하는 3단계 힌트
- OpenAI, Gemini, Anthropic 구조화 리뷰 어댑터와 환경변수 기반 provider 선택
- CLI 정상·오류 입력 단위 테스트
- 실제 `javac`/`java`로 오답 제출, 힌트, 편집과 통과 재제출을 검증하는 CLI workflow E2E 테스트
- 마지막 제출이 미통과인 기존 세션의 재개와 새 문제 선택
- `RESUBMIT_EDITOR`, `EDITOR` 순서로 선택한 에디터 실행과 수동 편집 fallback
- 빈 줄과 들여쓰기로 정렬된 상태 표·실패 항목·리뷰·명령 영역을 구분하는 plain CLI 출력
- 대화형 터미널의 화면 갱신과 에디터 실행 전후 화면 복원
- 외부 LLM 리뷰 요청 중 spinner와 경과 시간 표시
- Gradle build/test용 GitHub Actions workflow
- 내장 실행 문제 6개, 문제 검색과 카탈로그 탐색
- 자기보고·관찰 결과를 구분한 초기 진단, 프로필과 규칙 기반 다음 문제 추천
- 소스 해시·위치·규칙 ID를 포함한 Java AST 근거와 리뷰의 근거 ID 검증
- 고정 8개 사례의 실제 Gemini 응답·항목별 판정·토큰 및 요청 시간 기록

구현 여부는 이 목록과 실제 코드·테스트 결과를 함께 갱신한다.

## 범위

### 첫 실행 흐름

- 내장 실행 문제 6개 중 하나 선택
- `Solution.java` 제출
- 실제 `javac`/`java` 프로세스 실행
- compile failure, wrong answer, pass, timeout 구분
- 구조화된 리뷰 응답과 3단계 힌트
- API 키 없이 확인할 수 있는 fixture mode
- session/attempt 단위 JSONL 이력
- 핵심 결정론적 테스트와 GitHub Actions

### 초기 계획의 현재 상태

- 자기보고와 관찰 결과를 분리한 초기 진단: 구현됨
- 4~6문제 진단 workflow: 초기 목표이며 현재는 4문제 구성으로 구현됨
- 단계적 힌트와 재제출: 구현됨
- SQLite 이력 저장: 미구현, 현재 JSONL 파일 사용
- 학습 프로필과 다음 문제 추천: 로컬 기록·카탈로그 기반으로 구현됨
- 선택적 Neo4j 선수 지식 탐색: 미구현
- Spring Boot API adapter: 미구현

### 현재 범위에서 제외

- 웹 UI와 로그인
- 외부 사용자의 코드를 받는 원격 실행 서비스
- 마이크로서비스
- 초기 단계의 Neo4j·SQLite 이중 저장
- 모든 PR에서 실행하는 유료 LLM 평가
- AWS 배포

## 아키텍처 방향

아래는 초기 설계의 개념도이며 클래스명 목록이 아니다. 현재 실행 조율은 `CodingCoachCli`에 있고 상태 전이는 `SubmissionStateMachine`이 검사한다. 컴파일·테스트 실행, 리뷰와 JSONL 저장은 별도 컴포넌트를 사용한다. CLI와 workflow의 완전한 분리는 후속 정리 대상이다. 도식은 구현 완료 기능을 추가로 나타내지 않는다.

```text
coding-test fixtures (read-only test data)
                    │
                    ▼
              Kotlin CLI
                    │
                    ▼
          AssessmentWorkflow
             │            │
             ▼            ▼
         CodeRunner   AttemptRepository
             │            JSONL
             ▼
       ExecutionResult
             │
             ▼
        ReviewProvider
          │         │
          ▼         ▼
       Fixture   External LLM
```

- workflow가 상태 전이를 관리한다.
- runner가 compile/test 결과를 결정한다.
- reviewer는 실행 결과를 설명하고 힌트를 생성한다.
- repository는 실행과 평가 근거를 저장한다.
- CLI 입출력은 핵심 workflow와 분리한다.

상세 결정과 변경 이유는 [`docs/adr`](docs/adr/README.md)에 기록한다. CLI의 목표 사용자 흐름과 화면 명세는 [`docs/product/cli-ux.md`](docs/product/cli-ux.md)에 기록한다.

## 테스트 자료

`coding-test/`는 기존 Java 풀이와 학습 기록을 보관한 선택적 로컬 자료이며 Git에 포함하지 않는다. 이 폴더 없이도 내장 문제와 자동 데모를 실행할 수 있다. 공개본에는 외부 문제 CSV를 포함하지 않는다. 내장 6개 연습 fixture는 일반적인 알고리즘 유형을 바탕으로 설명과 입력 예제를 이 공개본용으로 작성했다. 개인 자료는 RESUBMIT_CODE_RPG_HOME을 설정한 경우에만 로컬에서 읽으며, 외부 행은 자동 채점 fixture로 취급하지 않는다. LLM 골든셋은 외부 문제 데이터 없이 직접 작성한 작은 배열 합계 문제를 사용한다.

- Adaptive Coding Coach 애플리케이션 코드와 분리한다.
- 원본 fixture는 테스트 과정에서 수정하지 않는다.
- 과거 기록을 새 runner가 생성한 결과처럼 사용하지 않는다.
- 자동화 테스트에는 필요한 최소 사례를 별도 test resource로 복제한다.

## 실행 방법

```bash
./gradlew build
./gradlew test
./gradlew -q run --console=plain
```

현재 `run` 명령은 첫 실행에서 자기보고를 입력받아 홈 메뉴로 이동한다. 학습 메뉴에서 문제를 선택하면 `.resubmit/sessions/` 아래에 `Problem.md`와 `Solution.java`를 생성한다. CLI는 상태 제목, 파일 경로와 가능한 명령을 compact 형식으로 구분한다. 문제와 제출 파일 경로는 세션 생성·재개 직후에만 표시한다. 터미널 폭은 `/dev/tty`에서 확인하고, 감지할 수 없으면 `COLUMNS`, 80열 순서로 fallback한다. 출력은 자동 줄바꿈 경계를 피하도록 감지 폭보다 한 칸 짧게 표시하며, 긴 경로는 가운데를 `…`로 줄여 파일명을 보존한다. 지원하는 대화형 터미널에서는 alternate screen에 진입하지 않고 상태가 바뀔 때 현재 화면을 다시 그린다. 에디터 실행 후에는 CLI 화면을 다시 그린다. `RESUBMIT_NO_TUI`가 있거나 `TERM=dumb`이면 기존 순차 출력 방식을 사용한다. 지원하는 터미널에서는 제목을 bold로, PASS·FAIL·TIMEOUT을 ANSI 색상으로 표시하며 `NO_COLOR`가 있거나 `TERM=dumb`이면 일반 텍스트만 출력한다. 제출 대기 또는 리뷰 화면에서 `e`를 입력하면 `RESUBMIT_EDITOR`, `EDITOR` 순서로 선택한 명령에 파일 절대 경로를 전달하고, 프로세스 종료 후 제출 대기로 돌아온다. 두 환경변수가 없거나 실행에 실패하면 절대 경로를 표시해 수동 편집을 계속할 수 있다. 특정 IDE 자동 탐지와 백그라운드 GUI 에디터의 저장 완료 감지는 지원하지 않는다. 사용자가 템플릿을 편집한 뒤 Enter를 누르면 실제 `javac` 프로세스로 컴파일하고 고정 테스트를 별도 `java` 프로세스에서 실행한다. 제출 결과는 `.resubmit/history/attempts.jsonl`에 저장하고 화면에는 저장 여부만 표시한다. 에디터 명령과 API 키는 JSONL 이력에 저장하지 않는다. 실행 결과 기반 fixture 리뷰는 환경변수 없이 동작하며, 외부 LLM 리뷰 요청 중에는 대화형 터미널에 spinner와 경과 시간을 표시한다. 외부 LLM은 아래 환경변수를 설정한 경우에만 호출한다.

## CI

GitHub Actions는 pull request와 기본 브랜치 push에서 다음 검증을 수행하도록 준비한다.

1. 저장소 checkout
2. JDK 21과 Gradle 설정
3. `./gradlew build`

로컬에서는 JDK 21 환경에서 `./gradlew build`와 `./gradlew run`을 검증한다. Actions 결과는 README의 구현 상태를 갱신할 때 함께 확인한다.

fixture 기반 제출 데모는 실제 `javac`와 별도 `java` 프로세스를 사용하며 다음 명령으로 반복 실행할 수 있다.

```bash
./gradlew test --tests '*CliWorkflowE2eTest'
```

실제 API를 사용하는 LLM 평가는 기본 CI에 넣지 않는다. 기본 테스트는 고정 실행 라벨과 저장된 실제 응답의 파싱·근거 ID 검증을 수행한다. 유료 반복 평가는 별도 Python 도구로 수동 실행하며 호출 전에 모델·사례·횟수·전송 범위를 승인받는다. 현재 HEAD의 GitHub Actions 성공 여부는 로컬 빌드 결과와 별도로 확인해야 한다.

## 리뷰 provider 설정

기본 provider는 `fixture`다. 외부 provider를 선택하면 제출 코드, 문제 설명과 테스트 결과가 해당 API로 전송된다. API 키는 JSONL 이력에 저장하지 않는다. 리뷰에 사용한 모델명과 프롬프트 버전은 재현을 위한 이력에 저장한다. 재제출은 이전 코드·결과·열람 힌트도 리뷰 문맥에 포함할 수 있다.

```text
RESUBMIT_REVIEW_PROVIDER=openai
OPENAI_API_KEY
OPENAI_MODEL

RESUBMIT_REVIEW_PROVIDER=gemini
GEMINI_API_KEY
GEMINI_MODEL

RESUBMIT_REVIEW_PROVIDER=anthropic
ANTHROPIC_API_KEY
ANTHROPIC_MODEL
```

선택한 provider의 API 키나 모델 환경변수가 없으면 실행 결과는 저장하고 리뷰를 `UNAVAILABLE`로 기록한다. 실제 외부 API 호출 테스트는 기본 CI에서 실행하지 않는다.

## 현재 한계

- 로컬에서 신뢰하는 Java 코드 실행을 전제로 한다. 프로세스 timeout·출력 제한은 있지만 파일·네트워크 접근과 메모리를 제한하는 보안 sandbox는 없다.
- 근거 ID가 유효해도 설명이 정확하다는 뜻은 아니다. AST 근거는 Java 구문 수준이며 타입 해석·복잡도 증명·저장소 전체 영향 분석은 제공하지 않는다.
- 실제 API 평가는 Gemini 한 모델과 작은 배열 합계 문제의 변형에 한정된다. Codex의 원본 검토로 채점했으며 독립적인 사람의 검수와 실제 사용자 A/B 테스트는 하지 않았다.
- 자동 CLI 데모는 fixture 응답과 스크립트의 코드 교체를 사용한다. 실제 API에 연결한 CLI 전체 데모나 사람의 학습 효과를 검증한 결과와 구분한다.
- 요청 시간·토큰은 평가 실행 자료에 기록한다. 상용 모니터링이나 비용·지연 최적화 성과를 입증하지 않는다.
