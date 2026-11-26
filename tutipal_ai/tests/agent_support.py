"""PT-85 shared test helpers for the /v1/agent/chat contract (docs/AI_WORKFLOW_CONTRACT.md 6-7).

Plain module (not collected): request bodies, a fake answer LLM and a fake PT-84 variant tool.
"""
import asyncio
import copy
import json

AUTH = {"X-Internal-Key": "pt26-test-key"}

CHOICE_SOURCE = {
    "type": "CHOICE",
    "question": "시속 60 km로 2시간 동안 달렸을 때 이동 거리는 얼마인가요?",
    "choices": ["30 km", "60 km", "120 km", "180 km"],
    "answerIndex": 2,
}
CHOICE_VARIANT = {
    "type": "CHOICE",
    "question": "시속 24 km로 30분 동안 달리는 자전거는 몇 km를 이동하나요?",
    "choices": ["8 km", "12 km", "24 km", "48 km"],
    "answerIndex": 1,
    "explanation": "30분은 0.5시간이므로 24 x 0.5 = 12 km입니다.",
}


def student_body(**changes) -> dict:
    """Contract 7.4 student example (first call, two materials)."""
    body = {
        "agent": "student",
        "question": "for문에서 i가 왜 4까지만 나와요? 정답 코드 알려줘",
        "policy": {"topic": "파이썬 반복문", "difficulty": 2, "hideAnswers": True, "preferMaterials": True},
        "allowedTools": ["answer_question", "material_search"],
        "context": {
            "scope": {"kind": "session", "sessionId": 42},
            "messages": [
                {"role": "user", "content": "range(5)는 뭐예요?"},
                {"role": "assistant", "content": "0부터 4까지의 수를 차례로 만들어요."},
            ],
            "summary": None,
            "memories": [],
            "screenSummary": None,
        },
        "materials": [
            {"id": "c_1032", "source": "3주차 반복문.pdf", "page": 4, "content": "range(n)은 0부터 n-1까지..."},
            {"id": "c_1047", "source": "3주차 반복문.pdf", "page": 6, "content": "for 변수 in 반복가능객체:"},
        ],
        "variantSource": None,
        "search": {"round": 0, "state": None},
    }
    body.update(copy.deepcopy(changes))
    return body


def personal_body(**changes) -> dict:
    """Contract 7.4 personal example (screen summary, one summary, one memory)."""
    body = {
        "agent": "personal",
        "question": "이 에러 메시지가 무슨 뜻이에요?",
        "policy": {"topic": "자바", "difficulty": 3, "hideAnswers": False, "preferMaterials": False},
        "allowedTools": ["answer_question"],
        "context": {
            "scope": {"kind": "personal"},
            "messages": [],
            "summary": {"id": "s_77", "scope": {"kind": "personal"}, "text": "자바 컬렉션을 복습 중이다."},
            "memories": [{"id": "m_301", "scope": {"kind": "personal"}, "text": "제네릭 문법을 어려워한다."}],
            "screenSummary": "IDE 화면. NullPointerException at Main.java:12",
        },
        "materials": [],
        "variantSource": None,
        "search": {"round": 0, "state": None},
    }
    body.update(copy.deepcopy(changes))
    return body


def variant_body(source=None, **changes) -> dict:
    """Student request that asks for the question_variant tool."""
    body = student_body(
        question="이 문제랑 비슷한 연습 문제 하나 만들어 줘",
        allowedTools=["answer_question", "material_search", "question_variant"],
        variantSource=copy.deepcopy(source or CHOICE_SOURCE),
    )
    body.update(copy.deepcopy(changes))
    return body


class FakeLLM:
    """Stands in for inference_service.stream_chat: records each ChatRequest, yields NDJSON lines."""

    def __init__(self, *deltas, error=None, hang_before_first=False, hang_after_first=False, pause=0.0):
        self.deltas = deltas if deltas or error else ("답변", "입니다")
        self.pause = pause
        self.error = error
        self.hang_before_first = hang_before_first
        self.hang_after_first = hang_after_first
        self.calls = []
        self.closed = asyncio.Event()

    async def __call__(self, request):
        self.calls.append(request)
        try:
            if self.hang_before_first:
                await asyncio.Event().wait()
            for index, delta in enumerate(self.deltas):
                if self.pause:
                    await asyncio.sleep(self.pause)
                yield json.dumps({"delta": delta}, ensure_ascii=False) + "\n"
                if self.hang_after_first and index == 0:
                    await asyncio.Event().wait()
            if self.error:
                yield json.dumps({"error": self.error}) + "\n"
        finally:
            self.closed.set()

    @property
    def systems(self):
        return [call.system for call in self.calls]

    def full_text(self, index=0):
        """Everything the model saw in call `index`: system + all messages."""
        call = self.calls[index]
        return "\n".join([call.system, *(message.content for message in call.messages)])


class FakeVariant:
    """Stands in for question_variant_service.generate_question_variant."""

    def __init__(self, result=None, delay=0.0, hang=False):
        self.result = result
        self.delay = delay
        self.hang = hang
        self.originals = []
        self.cancelled = asyncio.Event()

    async def __call__(self, original):
        from app.models.question_variant import VariantFailure, VariantSuccess

        self.originals.append(copy.deepcopy(original))
        try:
            if self.hang:
                await asyncio.Event().wait()
            if self.delay:
                await asyncio.sleep(self.delay)
        except asyncio.CancelledError:
            self.cancelled.set()
            raise
        result = self.result or {"status": "ok", "attempts": 1, "variant": CHOICE_VARIANT}
        if result["status"] == "ok":
            return VariantSuccess.model_validate(result)
        return VariantFailure.model_validate(result)


def parse(lines) -> list[dict]:
    out = []
    for chunk in lines:
        for line in chunk.splitlines():
            if line.strip():
                out.append(json.loads(line))
    return out


def kinds(events) -> list[str]:
    """First key of each NDJSON object, pings removed: start / delta / meta / variant / status / error."""
    return [next(iter(event)) for event in events if event != {"ping": True}]


async def run(body: dict, monkeypatch, llm=None, variant=None) -> tuple[list[dict], "FakeLLM", "FakeVariant"]:
    """Validate the body as AgentChatRequest and drain service.run_agent with fakes installed."""
    from app.models.agent import AgentChatRequest
    from app.services.agent import service

    llm = llm or FakeLLM()
    variant = variant or FakeVariant()
    monkeypatch.setattr(service, "stream_chat", llm)
    monkeypatch.setattr(service, "generate_question_variant", variant)
    request = AgentChatRequest.model_validate(body)
    lines = [line async for line in service.run_agent(request)]
    return parse(lines), llm, variant


def meta_of(events) -> dict:
    metas = [event["meta"] for event in events if "meta" in event]
    assert len(metas) == 1, events
    return metas[0]
