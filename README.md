# Adaptive Coding Coach

코딩 테스트 제출을 직접 실행하고, 실행 결과를 근거로 단계적 힌트를 제공하는 개인용 학습 도구다.

현재 저장소는 설계와 CI 골격을 작성한 초기 단계다. 애플리케이션 코드는 아직 구현하지 않았다.

## 목표

첫 번째 실행 가능한 흐름은 다음과 같다.

```text
Kotlin CLI 실행
  → 문제 한 개 표시
  → Java 제출 파일 입력
  → javac 컴파일
  → 고정 테스트 실행
  → 결정론적 결과 생성
  → 결과에 근거한 구조화된 리뷰와 1단계 힌트 출력
  → 제출·평가 이력 저장
```

정오답, 컴파일 오류, 시간 초과는 코드 실행 결과로 판정한다. LLM은 판정을 변경하지 않고 실패 원인 설명과 단계적 힌트 생성에만 사용한다.

## 현재 상태

### 작성됨

- 프로젝트 목표와 첫 실행 흐름
- 초기 아키텍처 결정 기록(ADR)
- Gradle build/test용 GitHub Actions workflow 골격

### 아직 구현되지 않음

- Kotlin/Gradle 프로젝트와 Gradle Wrapper
- CLI와 상태 머신
- Java compile/test runner
- LLM 및 fixture review provider
- JSONL 실행 이력
- 자동화 테스트와 실행 데모

구현 여부는 이 목록과 실제 코드·테스트 결과를 함께 갱신한다.

## 범위

### 첫 실행 흐름

- 문제 1개
- `Solution.java` 제출
- 실제 `javac`/`java` 프로세스 실행
- compile failure, wrong answer, pass, timeout 구분
- 구조화된 리뷰 응답과 1단계 힌트
- API 키 없이 확인할 수 있는 fixture mode
- session/attempt 단위 JSONL 이력
- 핵심 결정론적 테스트와 GitHub Actions

### 첫 주 이후

- 자기보고와 관찰 결과를 분리한 초기 진단
- 4~6문제 진단 workflow
- 단계적 힌트와 재제출
- SQLite 이력 저장
- 학습 프로필과 다음 문제 추천
- 선택적 Neo4j 선수 지식 탐색
- Spring Boot API adapter

### 현재 범위에서 제외

- 웹 UI와 로그인
- 외부 사용자의 코드를 받는 원격 실행 서비스
- 마이크로서비스
- 초기 단계의 Neo4j·SQLite 이중 저장
- 모든 PR에서 실행하는 유료 LLM 평가
- AWS 배포

## 아키텍처 방향

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

상세 결정과 변경 이유는 [`docs/adr`](docs/adr/README.md)에 기록한다.

## 테스트 자료

`coding-test/`는 기존 Java 풀이와 학습 기록을 보관한 fixture 저장소다.

- Adaptive Coding Coach 애플리케이션 코드와 분리한다.
- 원본 fixture는 테스트 과정에서 수정하지 않는다.
- 과거 기록을 새 runner가 생성한 결과처럼 사용하지 않는다.
- 자동화 테스트에는 필요한 최소 사례를 별도 test resource로 복제한다.

## 예정 실행 방법

아래 명령은 Gradle Wrapper와 애플리케이션 코드가 추가된 뒤 사용할 계약이다. 현재는 실행할 수 없다.

```bash
./gradlew build
./gradlew test
./gradlew run --args="demo --submission samples/WrongSolution.java"
```

실제 명령이 구현되면 이 절을 실행 결과와 함께 갱신한다.

## CI

GitHub Actions는 pull request와 기본 브랜치 push에서 다음 검증을 수행하도록 준비한다.

1. Gradle Wrapper 검증
2. JDK 21 설정
3. `./gradlew build`

현재 workflow는 Gradle Wrapper가 아직 없으므로 통과하지 않는다. 첫 Kotlin 프로젝트 생성 시 wrapper를 커밋하고 로컬 `./gradlew build` 결과와 Actions 결과를 함께 확인한다.

실제 API를 사용하는 LLM 평가는 기본 CI에 넣지 않는다. 작은 고정 fixture 검증을 먼저 추가하고, 유료 평가는 이후 수동 workflow로 분리한다.

## 환경변수 계획

실제 LLM 연결을 추가할 때 자격 증명과 변경 가능한 모델 설정을 코드에 넣지 않는다.

```text
OPENAI_API_KEY
OPENAI_MODEL
```

fixture mode는 환경변수 없이 동작하게 만든다.

