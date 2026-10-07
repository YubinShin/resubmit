# ADR 0002: 실행 판정과 LLM 판단을 분리한다

- Status: Accepted
- Date: 2026-10-07

## Context

LLM은 코드의 접근법과 실패 원인을 설명하고 제출 코드에 맞는 힌트를 만들 수 있다. 반면 compile failure, wrong answer, timeout, pass는 동일 입력에서 재현 가능해야 한다. LLM 응답이 정오답을 변경하면 실행 기록을 신뢰하기 어렵다.

## Decision

`CodeRunner`가 컴파일과 테스트 결과를 결정하고, `ReviewProvider`는 확정된 `ExecutionResult`를 입력으로 받는다.

- `CodeRunner`는 compile failure, test failure, pass, timeout, runner error를 구분한다.
- LLM은 판정 결과를 변경할 수 없다.
- 리뷰 응답은 failure type, related skills, evidence, hint level, hint를 포함한 schema로 검증한다.
- timeout, API 오류, schema 오류는 리뷰 실패로 기록하되 제출 workflow는 계속 진행한다.
- API 키가 없는 실행과 자동화 테스트에는 같은 인터페이스의 fixture provider를 사용한다.

## Consequences

- 정오답과 실행 시간은 반복 검증할 수 있다.
- LLM 장애가 코드 실행 결과를 유실시키지 않는다.
- fixture와 실제 LLM 응답의 품질 차이를 별도로 설명해야 한다.
- 리뷰 schema와 prompt version을 실행 이력에 남겨야 한다.

