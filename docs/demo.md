# CLI 데모 재현

로컬 Java 실행으로 판정하고 fixture 리뷰의 힌트를 순서대로 공개한 뒤 재제출하는 데모다. API 호출은 없으며 실제 LLM 품질 평가와 분리한다. 실제 Gemini 응답을 사용한 반복 평가와 원본은 [평가 기록](evaluation.md)에 있다.

## 자동 재현

JDK 21과 Python 3.9 이상이 필요하다. 저장소 루트에서 실행한다. 첫 Gradle 실행에는 배포본과 의존성 다운로드를 위한 네트워크가 필요할 수 있다.

```bash
./gradlew installDist
python3 tools/run_fixture_demo.py
```

스크립트는 배포용 `build/install/resubmit/bin/resubmit`을 별도 프로세스로 실행한다. 새 임시 폴더를 작업 디렉터리로 사용하므로 저장소의 `.resubmit/`와 개인 기록은 읽거나 바꾸지 않는다. 데모용 가상 프로필을 미리 넣어 초기 자기보고 입력을 건너뛴다. provider는 fixture로 강제하며 API 키를 자식 프로세스에 전달하지 않는다. UI는 순차 텍스트 출력으로 실행하므로 대화형 화면 갱신이나 GUI 에디터는 이 데모의 검증 대상이 아니다.

자동으로 수행하는 단계는 다음과 같다.

1. 학습 시작 → 문제 검색 → 부호 선택 합계 → 풀이 시작을 선택한다.
2. 두 재귀 분기가 모두 더하기를 하는 오답 코드를 제출한다.
3. 실제 javac/java 결과가 고정 테스트 1/4 통과인지 확인한다.
4. h를 세 번 입력해 1·2·3단계 힌트를 순서대로 연다.
5. 스크립트가 두 번째 분기를 빼기로 고친 코드를 파일에 쓴다.
6. 재제출 후 고정 테스트 4/4 통과, 제출 이력 2회, 힌트 3단계 사용 기록을 확인하고 종료한다.

코드는 미리 준비한 두 버전을 스크립트가 교체한다. 사람이 힌트를 보고 독립적으로 수정했다는 증거나 학습 효과 측정이 아니다. fixture 힌트도 문제별 고정 문구이므로 임의의 제출 코드에 대한 설명 품질을 보장하지 않는다. 정해진 테스트 통과는 모든 입력의 정답을 보장하지 않는다.

결과는 `build/cli-demo/summary.json`과 `build/cli-demo/transcript.txt`에 생성한다. 원본 세션과 JSONL 이력이 남는 임시 작업 폴더도 출력한다. transcript의 임시 경로는 `$DEMO_WORKSPACE`로 치환한다. 실행 시각과 javac/java 소요 시간은 실행마다 달라진다.

## 확인한 실행 결과

2026-10-27, macOS·Temurin JDK 21.0.11에서 실행했다. 첫 sandbox 실행은 하위 프로세스의 `sysctl` 접근 제한으로 RUNNER_ERROR가 발생했고, 권한을 허용한 로컬 실행에서 다음 결과를 확인했다. 이는 애플리케이션의 코드 실행 보안 격리를 구현했다는 뜻이 아니다.

```json
{
  "provider": "fixture",
  "apiCalls": 0,
  "exitCode": 0,
  "attempts": 2,
  "firstPassedCount": 1,
  "lastPassedCount": 4,
  "testCount": 4,
  "maxHintLevelViewed": 3,
  "syntheticProfile": true,
  "scriptedEdit": true
}
```

원본 summary에서 임시 작업 폴더 경로만 위 예시에서 제외했다. [실행 출력](demo-fixture-transcript.txt)은 실제 프로세스 stdout을 경로 치환 후 보관한 것이다. 명령 입력은 별도 echo하지 않아 메뉴 프롬프트가 다음 출력과 같은 줄에 보일 수 있다.

## 직접 조작

```bash
RESUBMIT_REVIEW_PROVIDER=fixture ./gradlew -q run --console=plain
```

첫 실행은 자기보고를 입력한 뒤 홈 메뉴로 이동한다. 학습 시작 → 문제 검색에서 부호 선택 합계를 선택하고 표시된 Solution.java를 편집한다. Enter로 제출, h로 다음 힌트, e로 다시 편집한 뒤 Enter로 재제출한다. 에디터 설정 예시는 `.env.example`에 있다. `.env`는 자동으로 로드하지 않는다.

기존 기록이 있으면 이어 풀기가 제안될 수 있다. 자동 데모는 매번 별도 임시 폴더를 사용해 이 영향을 피한다. 외부 LLM을 선택한 실행은 코드와 실행 근거를 API에 전송하므로 이 무호출 데모 명령과 구분한다.
