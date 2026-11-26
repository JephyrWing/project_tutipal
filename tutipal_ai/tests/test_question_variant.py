import asyncio
import json
import os
import unittest
from unittest.mock import patch

import httpx

from app.clients import ollama_client, openai_client
from app.clients.ollama_client import ModelUnavailable
from app.main import app
from app.services import inference_service, question_variant_service
from app.services.question_variant_service import generate_question_variant

CHOICE = {
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
    "explanation": "30분은 0.5시간이며 거리 = 속력 × 시간이므로 24 × 0.5 = 12 km입니다.",
}
TEXT = {
    "type": "TEXT",
    "question": "어떤 수의 25%가 15입니다. 그 수를 구하고 풀이를 설명하세요.",
    "modelAnswer": "15를 0.25로 나누면 60입니다.",
}
TEXT_VARIANT = {
    "type": "TEXT",
    "question": "작년보다 20% 늘어난 도서관 방문자가 144명입니다. 작년 방문자 수를 구하세요.",
    "modelAnswer": "120명",
    "explanation": "작년 방문자의 120%가 144명이므로 144 ÷ 1.2 = 120명입니다.",
}
VERIFIED = {
    "answer_explanation_consistent": True,
    "same_concept": True,
    "distinct_application": True,
}


class QuestionVariantTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        settings = patch.dict(os.environ, {"MODEL_PROVIDER": "ollama"})
        settings.start()
        self.addCleanup(settings.stop)
        self.calls = []

    async def run_with_provider(self, original, *responses):
        pending = iter(responses)

        async def chat_stream(settings, messages, json_mode):
            self.assertTrue(json_mode)
            self.assertEqual(messages[0]["role"], "system")
            self.assertTrue(all(message["role"] == "user" for message in messages[1:]))
            payload = json.loads("".join(message["content"] for message in messages[1:]))
            self.calls.append(payload)
            response = next(pending)
            if isinstance(response, Exception):
                raise response
            raw = response if isinstance(response, str) else json.dumps(response, ensure_ascii=False)
            yield raw[:10]
            yield raw[10:]

        with patch.object(ollama_client, "chat_stream", chat_stream):
            result = await generate_question_variant(original)
        self.assertEqual(len(self.calls), len(responses))
        return result.model_dump(exclude_none=True)

    def assert_failed(self, result, attempts, *reasons):
        self.assertEqual(result, {"status": "failed", "attempts": attempts, "failure_reasons": list(reasons)})

    async def test_choice_success(self):
        result = await self.run_with_provider(CHOICE, CHOICE_VARIANT, VERIFIED)
        self.assertEqual(result, {"status": "ok", "attempts": 1, "variant": CHOICE_VARIANT})
        self.assertEqual(self.calls[0], {"original": CHOICE, "retry_feedback": []})
        self.assertEqual(self.calls[1], {"original": CHOICE, "variant": CHOICE_VARIANT})

    async def test_text_success(self):
        result = await self.run_with_provider(TEXT, TEXT_VARIANT, VERIFIED)
        self.assertEqual(result, {"status": "ok", "attempts": 1, "variant": TEXT_VARIANT})

    async def test_spring_choice_count_boundary_and_unused_null(self):
        original = {**CHOICE, "choices": ["30 km", "120 km"], "answerIndex": 1, "modelAnswer": None}
        variant = {**CHOICE_VARIANT, "choices": [*CHOICE_VARIANT["choices"], "96 km"]}
        result = await self.run_with_provider(original, variant, VERIFIED)
        self.assertEqual(result["variant"], variant)
        self.assertNotIn("modelAnswer", self.calls[0]["original"])

    async def test_student_answer_is_rejected_before_any_model_call(self):
        result = await self.run_with_provider({**TEXT, "studentAnswer": "비공개 학생 답안"})
        self.assert_failed(result, 0, "invalid_original")

    async def test_concept_mismatch(self):
        wrong_concept = {
            **TEXT_VARIANT,
            "question": "가로 12 m, 세로 10 m인 직사각형의 넓이를 구하세요.",
            "modelAnswer": "120 m²",
            "explanation": "직사각형 넓이는 가로 × 세로이므로 120 m²입니다.",
        }
        verdict = {**VERIFIED, "same_concept": False}
        result = await self.run_with_provider(TEXT, wrong_concept, verdict, wrong_concept, verdict, wrong_concept, verdict)
        self.assert_failed(result, 3, "concept_mismatch")
        self.assertIn("핵심 개념", self.calls[2]["retry_feedback"][0])

    async def test_duplicate_choices_are_rejected_without_model_verification(self):
        duplicate = {**CHOICE_VARIANT, "choices": ["12 km", "  12 km  ", "24 km", "48 km"]}
        result = await self.run_with_provider(CHOICE, duplicate, duplicate, duplicate)
        self.assert_failed(result, 3, "duplicate_choices")
        self.assertTrue(all("variant" not in payload for payload in self.calls))
        self.assertIn("중복", self.calls[1]["retry_feedback"][0])

    async def test_case_sensitive_code_choices_remain_distinct(self):
        original = {
            "type": "CHOICE",
            "question": "파이썬에서 참을 나타내는 불리언 리터럴은 무엇인가요?",
            "choices": ["True", "true"],
            "answerIndex": 0,
        }
        candidate = {
            "type": "CHOICE",
            "question": "파이썬에서 거짓을 나타내는 불리언 리터럴을 고르세요.",
            "choices": ["false", "False"],
            "answerIndex": 1,
            "explanation": "파이썬은 대소문자를 구분하고 거짓 리터럴은 False입니다.",
        }
        result = await self.run_with_provider(original, candidate, VERIFIED)
        self.assertEqual(result["variant"], candidate)

    async def test_normalized_copy_is_rejected_without_model_verification(self):
        copy = {**CHOICE_VARIANT, "question": CHOICE["question"].rstrip("?").replace("60 km", "60  km") + "？"}
        result = await self.run_with_provider(CHOICE, copy, copy, copy)
        self.assert_failed(result, 3, "same_question")
        self.assertTrue(all("variant" not in payload for payload in self.calls))

    async def test_paraphrase_is_rejected_by_verifier(self):
        paraphrase = {
            **CHOICE,
            "question": "두 시간 동안 60 km/h로 이동했다면 총 주행 거리는 얼마인가요?",
            "explanation": "거리 = 속력 × 시간이므로 60 × 2 = 120 km입니다.",
        }
        verdict = {**VERIFIED, "distinct_application": False}
        result = await self.run_with_provider(CHOICE, paraphrase, verdict, paraphrase, verdict, paraphrase, verdict)
        self.assert_failed(result, 3, "no_material_difference")
        self.assertIn("조건이나 사례", self.calls[2]["retry_feedback"][0])

    async def test_incorrect_answer_and_explanation_are_rejected(self):
        wrong_answer = {**CHOICE_VARIANT, "answerIndex": 0}
        verdict = {**VERIFIED, "answer_explanation_consistent": False}
        result = await self.run_with_provider(CHOICE, wrong_answer, verdict, wrong_answer, verdict, wrong_answer, verdict)
        self.assert_failed(result, 3, "answer_explanation_mismatch")

    async def test_retries_use_format_and_verifier_feedback_then_return_success(self):
        wrong_answer = {**CHOICE_VARIANT, "answerIndex": 0}
        verdict = {**VERIFIED, "answer_explanation_consistent": False}
        result = await self.run_with_provider(CHOICE, "not JSON", wrong_answer, verdict, CHOICE_VARIANT, VERIFIED)
        self.assertEqual(result, {"status": "ok", "attempts": 3, "variant": CHOICE_VARIANT})
        self.assertIn("JSON 필드", self.calls[1]["retry_feedback"][0])
        self.assertIn("정답과 일치", self.calls[3]["retry_feedback"][0])

    async def test_attempt_limit_returns_all_final_reasons_without_a_variant(self):
        verdict = {**VERIFIED, "same_concept": False, "distinct_application": False}
        result = await self.run_with_provider(CHOICE, CHOICE_VARIANT, verdict, CHOICE_VARIANT, verdict, CHOICE_VARIANT, verdict)
        self.assert_failed(result, 3, "concept_mismatch", "no_material_difference")
        self.assertEqual(len(self.calls[4]["retry_feedback"]), 2)

    async def test_invalid_generation_fields_do_not_reach_verifier(self):
        for candidate in (
            TEXT_VARIANT,
            {**CHOICE_VARIANT, "answerIndex": 4},
            {**CHOICE_VARIANT, "answerIndex": True},
            {**CHOICE_VARIANT, "explanation": "  "},
            {**CHOICE_VARIANT, "choices": ["12 km", "   "]},
        ):
            with self.subTest(candidate=candidate):
                self.calls.clear()
                result = await self.run_with_provider(CHOICE, candidate, candidate, candidate)
                self.assert_failed(result, 3, "invalid_format")

    async def test_malformed_verdict_cannot_pass(self):
        bad_verdict = {**VERIFIED, "same_concept": "true"}
        result = await self.run_with_provider(TEXT, TEXT_VARIANT, bad_verdict, TEXT_VARIANT, bad_verdict, TEXT_VARIANT, bad_verdict)
        self.assert_failed(result, 3, "invalid_verification")

    async def test_long_json_fits_existing_chat_request_limits(self):
        punctuation = "\\\""
        original = {**TEXT, "modelAnswer": punctuation * 1000}
        candidate = {**TEXT_VARIANT, "modelAnswer": punctuation * 1000, "explanation": '"' * 1000}
        result = await self.run_with_provider(original, candidate, VERIFIED)
        self.assertEqual(result["variant"], candidate)
        self.assertEqual(self.calls[1]["original"], original)

    async def test_provider_failure_during_verification_returns_no_candidate(self):
        result = await self.run_with_provider(TEXT, TEXT_VARIANT, ModelUnavailable("provider error"))
        self.assert_failed(result, 1, "model_unavailable")

    async def test_workflow_timeout_returns_failure(self):
        async def stall(settings, messages, json_mode):
            await asyncio.sleep(1)
            yield "{}"

        with (
            patch.object(ollama_client, "chat_stream", stall),
            patch.object(question_variant_service, "WORKFLOW_TIMEOUT_SECONDS", 0.01),
        ):
            result = await generate_question_variant(TEXT)
        self.assert_failed(result.model_dump(), 1, "model_unavailable")


class SharedChatRegressionTest(unittest.IsolatedAsyncioTestCase):
    async def test_chat_still_falls_back_only_before_the_first_delta(self):
        for emitted in (False, True):
            with self.subTest(emitted=emitted):
                fallback_calls = []

                async def local_stream(settings, messages, json_mode):
                    if emitted:
                        yield "local"
                    raise ModelUnavailable("provider error")

                async def fallback_stream(settings, messages, json_mode):
                    fallback_calls.append(messages)
                    yield "fallback"

                async def available_models(settings):
                    return {settings.name}

                with (
                    patch.dict(os.environ, {
                        "MODEL_PROVIDER": "auto",
                        "OPENAI_API_KEY": "fake-key",
                        "AI_INTERNAL_KEY": "test-key",
                    }),
                    patch.object(inference_service, "available_models", available_models),
                    patch.object(ollama_client, "chat_stream", local_stream),
                    patch.object(openai_client, "chat_stream", fallback_stream),
                ):
                    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                        response = await client.post(
                            "/v1/chat",
                            headers={"X-Internal-Key": "test-key"},
                            json={"system": "system", "messages": [{"role": "user", "content": "question"}]},
                        )

                self.assertEqual(response.status_code, 200)
                events = [json.loads(line) for line in response.text.splitlines()]
                if emitted:
                    self.assertEqual(events, [{"delta": "local"}, {"error": "llm_unavailable"}])
                    self.assertEqual(fallback_calls, [])
                else:
                    self.assertEqual(events, [{"delta": "fallback"}])
                    self.assertEqual(len(fallback_calls), 1)
