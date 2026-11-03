
from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


Role = Literal["operator", "manager", "admin"]


class LoginIn(BaseModel):
    username: str
    password: str


class TokenOut(BaseModel):
    access_token: str
    token_type: str = "bearer"


class UserCreate(BaseModel):
    full_name: str = Field(min_length=2, max_length=150)
    username: str = Field(min_length=3, max_length=80)
    password: str = Field(min_length=8, max_length=72)
    role: Role = "operator"


class UserOut(BaseModel):
    id: int
    full_name: str
    username: str
    role: str
    is_active: bool

    model_config = ConfigDict(from_attributes=True)


class CustomerCreate(BaseModel):
    full_name: str = Field(min_length=2, max_length=150)
    birth_date: date | None = None
    passport_number: str | None = None
    pinfl: str | None = Field(default=None, pattern=r"^\d{14}$")
    phone: str = Field(min_length=7, max_length=30)
    address: str | None = None


class CustomerOut(BaseModel):
    id: int
    full_name: str
    birth_date: date | None
    passport_number: str | None
    pinfl: str | None
    phone: str
    address: str | None
    created_by: int

    model_config = ConfigDict(from_attributes=True)


class ApplicationCreate(BaseModel):
    customer_id: int
    title: str = Field(min_length=2, max_length=200)
    description: str | None = None


class ApplicationReview(BaseModel):
    status: Literal["approved", "rejected"]
    comment: str | None = None


class ApplicationOut(BaseModel):
    id: int
    customer_id: int
    title: str
    description: str | None
    status: str
    created_by: int
    reviewed_by: int | None
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)
class AdminPasswordReset(BaseModel):
    username: str = Field(min_length=3, max_length=80)
    new_password: str = Field(min_length=8, max_length=72)
