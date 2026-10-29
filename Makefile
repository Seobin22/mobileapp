# 실행 비서 평가 세트(빌드문서 v2 §12.3, v3 §9.2, v3.1 §5.2, 폴더 선택 -Deval.dirs=v3_1). 실제 Claude API 를 부르므로 비용이 든다.
#   ANTHROPIC_API_KEY=sk-ant-... make eval   → evals/report.md
.PHONY: eval test
eval:
	./mvnw -B test -Deval=true -Dtest=EvalRunner -Dsurefire.failIfNoSpecifiedTests=false

test:
	./mvnw -B test
