from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

ChoiceText = Annotated[str, StringConstraints(min_length=1, max_length=200)]


class QuestionModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True)

    question: str = Field(min_length=1, max_length=1000)


class ChoiceQuestion(QuestionModel):
    type: Literal["CHOICE"]
    choices: list[ChoiceText] = Field(min_length=2, max_length=5)
    answerIndex: int = Field(ge=0)
    modelAnswer: None = None

    @model_validator(mode="after")
    def check_answer_index(self) -> "ChoiceQuestion":
        if self.answerIndex >= len(self.choices):
            raise ValueError("answerIndex must refer to an existing choice")
        return self


class TextQuestion(QuestionModel):
    type: Literal["TEXT"]
    modelAnswer: str = Field(min_length=1, max_length=2000)
    choices: None = None
    answerIndex: None = None


OriginalQuestion = Annotated[ChoiceQuestion | TextQuestion, Field(discriminator="type")]


class ChoiceVariant(ChoiceQuestion):
    explanation: str = Field(min_length=1, max_length=1000)


class TextVariant(TextQuestion):
    explanation: str = Field(min_length=1, max_length=1000)


VariantQuestion = Annotated[ChoiceVariant | TextVariant, Field(discriminator="type")]
FailureReason = Literal[
    "invalid_original",
    "invalid_format",
    "duplicate_choices",
    "same_question",
    "answer_explanation_mismatch",
    "concept_mismatch",
    "no_material_difference",
    "invalid_verification",
    "model_unavailable",
]


class VariantSuccess(BaseModel):
    status: Literal["ok"] = "ok"
    attempts: int
    variant: VariantQuestion


class VariantFailure(BaseModel):
    status: Literal["failed"] = "failed"
    attempts: int
    failure_reasons: list[FailureReason]


VariantResult = VariantSuccess | VariantFailure
