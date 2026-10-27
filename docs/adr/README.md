# Architecture Decision Records

이 디렉터리는 Adaptive Coding Coach의 중요한 설계 결정과 변경 이유를 기록한다.

| ADR | 결정 | 상태 |
|---|---|---|
| [0001](0001-start-with-kotlin-cli.md) | Kotlin CLI로 시작한다 | Accepted |
| [0002](0002-keep-verdicts-deterministic.md) | 실행 판정과 LLM 판단을 분리한다 | Accepted |
| [0003](0003-treat-coding-test-as-read-only-fixtures.md) | 기존 코딩 테스트 자료를 읽기 전용 fixture로 사용한다 | Accepted |
| [0004](0004-separate-claimed-and-observed-skill.md) | 자기보고와 관찰된 숙련도를 분리한다 | Accepted |
| [0005](0005-evaluate-agent-changes-with-curated-cases.md) | 선별된 사례로 agent 변경을 평가한다 | Accepted |
| [0006](0006-keep-local-cli-independent-of-database-server.md) | 로컬 CLI 실행에 데이터베이스 서버를 요구하지 않는다 | Proposed |
| [0007](0007-ground-agent-reviews-in-analysis-and-verification.md) | 분석·실행 근거로 리뷰와 코드 변경 검증을 확장한다 | Accepted |

ADR을 대체하는 결정이 생기면 기존 문서를 삭제하거나 내용을 덮어쓰지 않고 새 ADR에서 이전 결정을 `Superseded`로 연결한다.
