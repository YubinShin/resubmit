# ADR 0003: 기존 코딩 테스트 자료를 읽기 전용 fixture로 사용한다

- Status: Accepted
- Date: 2026-10-07

## Context

`coding-test/`에는 기존 Java 풀이, 오답 기록, 문제 목록과 학습 이력이 있다. 이 자료는 runner와 reviewer를 검증할 현실적인 입력이지만 Adaptive Coding Coach의 애플리케이션 코드는 아니다.

과거 기록에는 사후 정리된 데이터도 있으므로, 이를 새 workflow가 직접 생성한 실행 증거처럼 표시하면 안 된다.

## Decision

- `coding-test/`는 읽기 전용 원본 fixture로 취급한다.
- 애플리케이션 구현을 위해 기존 fixture 파일을 수정하지 않는다.
- 자동화 테스트에 필요한 사례는 출처를 기록하고 최소 형태로 test resource에 복제한다.
- 새 runner로 생성한 session, attempt, execution, review 이력은 별도 저장한다.
- 과거 학습 기록과 새 실행 결과를 README와 데모에서 구분한다.

## Consequences

- 기존 학습 자료를 훼손하지 않고 실제 사례를 재사용할 수 있다.
- 테스트 fixture와 운영 이력의 경계가 명확해진다.
- 복제한 fixture가 원본과 달라질 수 있으므로 출처와 변경 이유를 기록해야 한다.

