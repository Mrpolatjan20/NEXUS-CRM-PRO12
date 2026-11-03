
from datetime import datetime, timezone

from fastapi import FastAPI, Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session
from sqlalchemy import select

from .db import Base, engine, get_db
from . import models, schemas
from .security import (
    hash_password,
    verify_password,
    create_access_token,
    decode_access_token,
)


Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="NEXUS CRM PRO API",
    description="NEXUS CRM PRO management system",
    version="1.0.0",
)

bearer = HTTPBearer()


def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(bearer),
    db: Session = Depends(get_db),
):
    payload = decode_access_token(credentials.credentials)

    if not payload or not payload.get("sub"):
        raise HTTPException(
            status_code=401,
            detail="Login muddati tugagan yoki token noto'g'ri",
        )

    user = db.get(models.User, int(payload["sub"]))

    if not user or not user.is_active:
        raise HTTPException(status_code=401, detail="Foydalanuvchi topilmadi")

    return user


def require_roles(*roles):
    def checker(user=Depends(get_current_user)):
        if user.role not in roles:
            raise HTTPException(
                status_code=403,
                detail="Bu amal uchun ruxsatingiz yo'q",
            )
        return user

    return checker


def add_audit(db, user_id, action, details=None):
    log = models.AuditLog(
        user_id=user_id,
        action=action,
        details=details,
    )
    db.add(log)


@app.get("/")
def home():
    return {
        "name": "NEXUS CRM PRO",
        "status": "running",
        "version": "1.0.0",
    }


@app.get("/health")
def health():
    return {"status": "ok"}


# Birinchi administratorni faqat tizimda xodim bo'lmaganda yaratish
@app.post("/auth/bootstrap-admin", response_model=schemas.UserOut)
def bootstrap_admin(data: schemas.UserCreate, db: Session = Depends(get_db)):
    if db.scalar(select(models.User.id).limit(1)) is not None:
        raise HTTPException(
            status_code=403,
            detail="Administrator avval yaratilgan",
        )

    user = models.User(
        full_name=data.full_name,
        username=data.username,
        password_hash=hash_password(data.password),
        role="admin",
    )

    db.add(user)
    db.commit()
    db.refresh(user)

    add_audit(db, user.id, "admin_created", "Birinchi administrator yaratildi")
    db.commit()

    return user


@app.post("/auth/login", response_model=schemas.TokenOut)
def login(data: schemas.LoginIn, db: Session = Depends(get_db)):
    user = db.scalar(
        select(models.User).where(models.User.username == data.username)
    )

    if not user or not verify_password(data.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Login yoki parol noto'g'ri")

    if not user.is_active:
        raise HTTPException(status_code=403, detail="Hisob faol emas")

    token = create_access_token(user.id, user.role)

    return {"access_token": token, "token_type": "bearer"}


@app.get("/me", response_model=schemas.UserOut)
def me(user=Depends(get_current_user)):
    return user


# Xodimlarni faqat administrator yaratadi
@app.post("/users", response_model=schemas.UserOut)
def create_user(
    data: schemas.UserCreate,
    db: Session = Depends(get_db),
    admin=Depends(require_roles("admin")),
):
    exists = db.scalar(
        select(models.User).where(models.User.username == data.username)
    )

    if exists:
        raise HTTPException(status_code=409, detail="Login band")

    user = models.User(
        full_name=data.full_name,
        username=data.username,
        password_hash=hash_password(data.password),
        role=data.role,
    )

    db.add(user)
    db.flush()

    add_audit(db, admin.id, "user_created", f"Xodim ID: {user.id}")
    db.commit()
    db.refresh(user)

    return user


@app.get("/users", response_model=list[schemas.UserOut])
def list_users(
    db: Session = Depends(get_db),
    admin=Depends(require_roles("admin")),
):
    return db.scalars(select(models.User).order_by(models.User.id)).all()


# Mijoz qo'shish
@app.post("/customers", response_model=schemas.CustomerOut)
def create_customer(
    data: schemas.CustomerCreate,
    db: Session = Depends(get_db),
    user=Depends(require_roles("operator", "manager", "admin")),
):
    if data.passport_number:
        exists = db.scalar(
            select(models.Customer).where(
                models.Customer.passport_number == data.passport_number
            )
        )
        if exists:
            raise HTTPException(status_code=409, detail="Pasport raqami mavjud")

    if data.pinfl:
        exists = db.scalar(
            select(models.Customer).where(
                models.Customer.pinfl == data.pinfl
            )
        )
        if exists:
            raise HTTPException(status_code=409, detail="PINFL mavjud")

    customer = models.Customer(
        **data.model_dump(),
        created_by=user.id,
    )

    db.add(customer)
    db.flush()

    add_audit(db, user.id, "customer_created", f"Mijoz ID: {customer.id}")
    db.commit()
    db.refresh(customer)

    return customer


@app.get("/customers", response_model=list[schemas.CustomerOut])
def list_customers(
    db: Session = Depends(get_db),
    user=Depends(require_roles("operator", "manager", "admin")),
):
    return db.scalars(
        select(models.Customer).order_by(models.Customer.id.desc())
    ).all()


# Ariza yaratish
@app.post("/applications", response_model=schemas.ApplicationOut)
def create_application(
    data: schemas.ApplicationCreate,
    db: Session = Depends(get_db),
    user=Depends(require_roles("operator", "manager", "admin")),
):
    customer = db.get(models.Customer, data.customer_id)

    if not customer:
        raise HTTPException(status_code=404, detail="Mijoz topilmadi")

    application = models.Application(
        **data.model_dump(),
        created_by=user.id,
        status="pending",
    )

    db.add(application)
    db.flush()

    add_audit(db, user.id, "application_created", f"Ariza ID: {application.id}")
    db.commit()
    db.refresh(application)

    return application


@app.get("/applications", response_model=list[schemas.ApplicationOut])
def list_applications(
    db: Session = Depends(get_db),
    user=Depends(require_roles("operator", "manager", "admin")),
):
    return db.scalars(
        select(models.Application).order_by(models.Application.id.desc())
    ).all()


# Arizani menejer yoki administrator ko'rib chiqadi
@app.patch("/applications/{application_id}/review",
           response_model=schemas.ApplicationOut)
def review_application(
    application_id: int,
    data: schemas.ApplicationReview,
    db: Session = Depends(get_db),
    manager=Depends(require_roles("manager", "admin")),
):
    application = db.get(models.Application, application_id)

    if not application:
        raise HTTPException(status_code=404, detail="Ariza topilmadi")

    if application.status != "pending":
        raise HTTPException(status_code=409, detail="Ariza avval ko'rib chiqilgan")

    application.status = data.status
    application.reviewed_by = manager.id
    application.reviewed_at = datetime.now(timezone.utc)

    add_audit(
        db,
        manager.id,
        "application_reviewed",
        f"Ariza ID: {application.id}; holati: {data.status}; izoh: {data.comment or ''}",
    )

    db.commit()
    db.refresh(application)

    return application


@app.get("/audit")
def list_audit(
    db: Session = Depends(get_db),
    admin=Depends(require_roles("admin")),
):
    logs = db.scalars(
        select(models.AuditLog).order_by(models.AuditLog.id.desc())
    ).all()

    return [
        {
            "id": log.id,
            "user_id": log.user_id,
            "action": log.action,
            "details": log.details,
            "created_at": log.created_at,
        }
        for log in logs
    ]
