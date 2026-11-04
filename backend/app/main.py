
import secrets
from datetime import datetime, timezone

from fastapi import (
    FastAPI,
    Depends,
    HTTPException,
    Header,
)
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session
from sqlalchemy import select, inspect, text

from .config import settings
from .db import Base, engine, get_db
from . import models, schemas
from .security import (
    hash_password,
    verify_password,
    create_access_token,
    decode_access_token,
)


Base.metadata.create_all(bind=engine)

# Existing database uchun mijoz rasmi ustunini qo'shish
customer_columns = {column["name"] for column in inspect(engine).get_columns("customers")}
with engine.begin() as connection:
    if "photo_data" not in customer_columns:
        connection.execute(text("ALTER TABLE customers ADD COLUMN photo_data TEXT"))
    if "offer_accepted" not in customer_columns:
        connection.execute(text("ALTER TABLE customers ADD COLUMN offer_accepted BOOLEAN NOT NULL DEFAULT 0"))
    if "personal_data_consent" not in customer_columns:
        connection.execute(text("ALTER TABLE customers ADD COLUMN personal_data_consent BOOLEAN NOT NULL DEFAULT 0"))

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
        raise HTTPException(
            status_code=401,
            detail="Foydalanuvchi topilmadi",
        )

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


# Birinchi administratorni yaratish
@app.post("/auth/bootstrap-admin", response_model=schemas.UserOut)
def bootstrap_admin(
    data: schemas.UserCreate,
    db: Session = Depends(get_db),
):
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

    add_audit(
        db,
        user.id,
        "admin_created",
        "Birinchi administrator yaratildi",
    )
    db.commit()

    return user


# Login
@app.post("/auth/login", response_model=schemas.TokenOut)
def login(
    data: schemas.LoginIn,
    db: Session = Depends(get_db),
):
    user = db.scalar(
        select(models.User).where(
            models.User.username == data.username
        )
    )

    if not user or not verify_password(
        data.password,
        user.password_hash,
    ):
        raise HTTPException(
            status_code=401,
            detail="Login yoki parol noto'g'ri",
        )

    if not user.is_active:
        raise HTTPException(
            status_code=403,
            detail="Hisob faol emas",
        )

    token = create_access_token(user.id, user.role)

    return {
        "access_token": token,
        "token_type": "bearer",
    }


# Administrator parolini xavfsiz tiklash
@app.post("/auth/reset-admin-password")
def reset_admin_password(
    data: schemas.AdminPasswordReset,
    x_admin_reset_key: str = Header(default=""),
    db: Session = Depends(get_db),
):
    if not settings.admin_reset_key:
        raise HTTPException(
            status_code=503,
            detail="Admin reset key sozlanmagan",
        )

    if not secrets.compare_digest(
        x_admin_reset_key,
        settings.admin_reset_key,
    ):
        raise HTTPException(
            status_code=403,
            detail="Ruxsat berilmadi",
        )

    user = db.scalar(
        select(models.User).where(
            models.User.username == data.username
        )
    )

    if not user or user.role != "admin":
        raise HTTPException(
            status_code=404,
            detail="Administrator topilmadi",
        )

    user.password_hash = hash_password(data.new_password)

    add_audit(
        db,
        user.id,
        "admin_password_reset",
        "Administrator paroli xavfsiz tiklandi",
    )

    db.commit()

    return {
        "message": "Administrator paroli yangilandi"
    }


# Joriy foydalanuvchi
@app.get("/me", response_model=schemas.UserOut)
def me(user=Depends(get_current_user)):
    return user


# Xodim yaratish
@app.post("/users", response_model=schemas.UserOut)
def create_user(
    data: schemas.UserCreate,
    db: Session = Depends(get_db),
    admin=Depends(require_roles("admin")),
):
    exists = db.scalar(
        select(models.User).where(
            models.User.username == data.username
        )
    )

    if exists:
        raise HTTPException(
            status_code=409,
            detail="Login band",
        )

    user = models.User(
        full_name=data.full_name,
        username=data.username,
        password_hash=hash_password(data.password),
        role=data.role,
    )

    db.add(user)
    db.flush()

    add_audit(
        db,
        admin.id,
        "user_created",
        f"Xodim ID: {user.id}",
    )

    db.commit()
    db.refresh(user)

    return user


# Xodimlar ro'yxati
@app.get("/users", response_model=list[schemas.UserOut])
def list_users(
    db: Session = Depends(get_db),
    admin=Depends(require_roles("admin")),
):
    return db.scalars(
        select(models.User).order_by(models.User.id)
    ).all()


# Mijoz yaratish
@app.post("/customers", response_model=schemas.CustomerOut)
def create_customer(
    data: schemas.CustomerCreate,
    db: Session = Depends(get_db),
    user=Depends(require_roles("operator", "manager", "admin")),
):
    if not data.offer_accepted or not data.personal_data_consent:
        raise HTTPException(
            status_code=400,
            detail="Ommaviy oferta va shaxsiy ma’lumotlarga rozilik talab qilinadi",
        )

    if data.passport_number:
        exists = db.scalar(
            select(models.Customer).where(
                models.Customer.passport_number
                == data.passport_number
            )
        )

        if exists:
            raise HTTPException(
                status_code=409,
                detail="Pasport raqami mavjud",
            )

    if data.pinfl:
        exists = db.scalar(
            select(models.Customer).where(
                models.Customer.pinfl == data.pinfl
            )
        )

        if exists:
            raise HTTPException(
                status_code=409,
                detail="PINFL mavjud",
            )

    customer = models.Customer(
        **data.model_dump(),
        created_by=user.id,
    )

    db.add(customer)
    db.flush()

    add_audit(
        db,
        user.id,
        "customer_created",
        f"Mijoz ID: {customer.id}",
    )

    db.commit()
    db.refresh(customer)

    return customer


# Mijozlar ro'yxati
@app.get("/customers", response_model=list[schemas.CustomerOut])
def list_customers(
    db: Session = Depends(get_db),
    user=Depends(require_roles("operator", "manager", "admin")),
):
    return db.scalars(
        select(models.Customer).order_by(
            models.Customer.id.desc()
        )
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
        raise HTTPException(
            status_code=404,
            detail="Mijoz topilmadi",
        )

    application = models.Application(
        **data.model_dump(),
        created_by=user.id,
        status="pending",
    )

    db.add(application)
    db.flush()

    add_audit(
        db,
        user.id,
        "application_created",
        f"Ariza ID: {application.id}",
    )

    db.commit()
    db.refresh(application)

    return application


# Arizalar ro'yxati
@app.get("/applications", response_model=list[schemas.ApplicationOut])
def list_applications(
    db: Session = Depends(get_db),
    user=Depends(require_roles("operator", "manager", "admin")),
):
    return db.scalars(
        select(models.Application).order_by(
            models.Application.id.desc()
        )
    ).all()


# Arizani menejer yoki administrator ko'rib chiqadi
@app.patch(
    "/applications/{application_id}/review",
    response_model=schemas.ApplicationOut,
)
def review_application(
    application_id: int,
    data: schemas.ApplicationReview,
    db: Session = Depends(get_db),
    manager=Depends(require_roles("manager", "admin")),
):
    application = db.get(
        models.Application,
        application_id,
    )

    if not application:
        raise HTTPException(
            status_code=404,
            detail="Ariza topilmadi",
        )

    if application.status != "pending":
        raise HTTPException(
            status_code=409,
            detail="Ariza avval ko'rib chiqilgan",
        )

    application.status = data.status
    application.reviewed_by = manager.id
    application.reviewed_at = datetime.now(timezone.utc)

    add_audit(
        db,
        manager.id,
        "application_reviewed",
        (
            f"Ariza ID: {application.id}; "
            f"holati: {data.status}; "
            f"izoh: {data.comment or ''}"
        ),
    )

    db.commit()
    db.refresh(application)

    return application


# Audit tarixi
@app.get("/audit")
def list_audit(
    db: Session = Depends(get_db),
    admin=Depends(require_roles("admin")),
):
    logs = db.scalars(
        select(models.AuditLog).order_by(
            models.AuditLog.id.desc()
        )
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


# Shartnoma so'rovini yuborish
@app.post("/contracts")
def create_contract(
    data: schemas.ContractCreate,
    db: Session = Depends(get_db),
    user=Depends(require_roles("operator", "manager", "admin")),
):
    customer = db.get(models.Customer, data.customer_id)
    if not customer:
        raise HTTPException(status_code=404, detail="Mijoz topilmadi")
    contract = models.Contract(
        contract_code="1000" + f"{secrets.randbelow(1000000):06d}",
        customer_id=customer.id,
        status="pending",
        created_by=user.id,
    )
    db.add(contract)
    db.flush()
    add_audit(db, user.id, "contract_requested", f"Shartnoma kodi: {contract.contract_code}")
    db.commit()
    db.refresh(contract)
    return {"id": contract.id, "contract_code": contract.contract_code, "customer_id": customer.id, "customer_name": customer.full_name, "status": contract.status, "created_at": contract.created_at}


@app.get("/contracts")
def list_contracts(db: Session = Depends(get_db), user=Depends(require_roles("operator", "manager", "admin"))):
    rows = db.execute(select(models.Contract, models.Customer).join(models.Customer, models.Contract.customer_id == models.Customer.id).order_by(models.Contract.id.desc())).all()
    return [{"id": c.id, "contract_code": c.contract_code, "customer_id": c.customer_id, "customer_name": customer.full_name, "status": c.status, "created_at": c.created_at, "reviewed_by": c.reviewed_by, "reviewed_at": c.reviewed_at} for c, customer in rows]


@app.patch("/contracts/{contract_id}/review")
def review_contract(contract_id: int, data: schemas.RecordReview, db: Session = Depends(get_db), manager=Depends(require_roles("manager", "admin"))):
    contract = db.get(models.Contract, contract_id)
    if not contract:
        raise HTTPException(status_code=404, detail="Shartnoma topilmadi")
    if contract.status != "pending":
        raise HTTPException(status_code=409, detail="Shartnoma avval ko'rib chiqilgan")
    contract.status = data.status
    contract.reviewed_by = manager.id
    contract.reviewed_at = datetime.now(timezone.utc)
    add_audit(db, manager.id, "contract_reviewed", f"Kod: {contract.contract_code}; status: {data.status}; izoh: {data.comment or ''}")
    db.commit()
    return {"id": contract.id, "status": contract.status}


# Chiqim tovarlar
@app.post("/stock-outs")
def create_stock_out(data: schemas.StockOutCreate, db: Session = Depends(get_db), user=Depends(require_roles("operator", "manager", "admin"))):
    contract = db.scalar(select(models.Contract).where(models.Contract.contract_code == data.contract_code))
    if not contract:
        raise HTTPException(status_code=404, detail="Shartnoma kodi topilmadi")
    if contract.status != "approved":
        raise HTTPException(status_code=409, detail="Chiqim uchun shartnoma avval tasdiqlangan bo'lishi kerak")
    item = models.StockOut(contract_id=contract.id, amount=data.amount, status="pending", created_by=user.id)
    db.add(item)
    db.flush()
    add_audit(db, user.id, "stock_out_created", f"Shartnoma: {contract.contract_code}; summa: {data.amount}")
    db.commit()
    db.refresh(item)
    return {"id": item.id, "contract_id": contract.id, "contract_code": contract.contract_code, "customer_name": db.get(models.Customer, contract.customer_id).full_name, "amount": item.amount, "status": item.status, "created_at": item.created_at}


@app.get("/stock-outs")
def list_stock_outs(db: Session = Depends(get_db), user=Depends(require_roles("operator", "manager", "admin"))):
    rows = db.execute(select(models.StockOut, models.Contract, models.Customer).join(models.Contract, models.StockOut.contract_id == models.Contract.id).join(models.Customer, models.Contract.customer_id == models.Customer.id).order_by(models.StockOut.id.desc())).all()
    return [{"id": item.id, "contract_id": contract.id, "contract_code": contract.contract_code, "customer_name": customer.full_name, "amount": item.amount, "status": item.status, "created_at": item.created_at} for item, contract, customer in rows]


@app.patch("/stock-outs/{item_id}/review")
def review_stock_out(item_id: int, data: schemas.RecordReview, db: Session = Depends(get_db), manager=Depends(require_roles("manager", "admin"))):
    item = db.get(models.StockOut, item_id)
    if not item:
        raise HTTPException(status_code=404, detail="Chiqim yozuvi topilmadi")
    if item.status != "pending":
        raise HTTPException(status_code=409, detail="Chiqim avval ko'rib chiqilgan")
    item.status = data.status
    item.reviewed_by = manager.id
    item.reviewed_at = datetime.now(timezone.utc)
    add_audit(db, manager.id, "stock_out_reviewed", f"Chiqim ID: {item.id}; status: {data.status}; izoh: {data.comment or ''}")
    db.commit()
    return {"id": item.id, "status": item.status}


# Fakturalar
@app.post("/invoices")
def create_invoice(data: schemas.InvoiceCreate, db: Session = Depends(get_db), user=Depends(require_roles("operator", "manager", "admin"))):
    contract = db.scalar(select(models.Contract).where(models.Contract.contract_code == data.contract_code))
    if not contract:
        raise HTTPException(status_code=404, detail="Shartnoma kodi topilmadi")
    if contract.status != "approved":
        raise HTTPException(status_code=409, detail="Faktura uchun shartnoma avval tasdiqlangan bo'lishi kerak")
    invoice = models.Invoice(contract_id=contract.id, supplier=data.supplier, amount=data.amount, status="pending", created_by=user.id)
    db.add(invoice)
    db.flush()
    add_audit(db, user.id, "invoice_created", f"Ta'minotchi: {invoice.supplier}; shartnoma: {contract.contract_code}; summa: {invoice.amount}")
    db.commit()
    db.refresh(invoice)
    return {"id": invoice.id, "supplier": invoice.supplier, "contract_id": contract.id, "contract_code": contract.contract_code, "amount": invoice.amount, "status": invoice.status, "created_at": invoice.created_at}


@app.get("/invoices")
def list_invoices(db: Session = Depends(get_db), user=Depends(require_roles("operator", "manager", "admin"))):
    rows = db.execute(select(models.Invoice, models.Contract).join(models.Contract, models.Invoice.contract_id == models.Contract.id).order_by(models.Invoice.id.desc())).all()
    return [{"id": invoice.id, "supplier": invoice.supplier, "contract_id": contract.id, "contract_code": contract.contract_code, "amount": invoice.amount, "status": invoice.status, "created_at": invoice.created_at} for invoice, contract in rows]


@app.patch("/invoices/{invoice_id}/review")
def review_invoice(invoice_id: int, data: schemas.RecordReview, db: Session = Depends(get_db), manager=Depends(require_roles("manager", "admin"))):
    invoice = db.get(models.Invoice, invoice_id)
    if not invoice:
        raise HTTPException(status_code=404, detail="Faktura topilmadi")
    if invoice.status != "pending":
        raise HTTPException(status_code=409, detail="Faktura avval ko'rib chiqilgan")
    invoice.status = data.status
    invoice.reviewed_by = manager.id
    invoice.reviewed_at = datetime.now(timezone.utc)
    add_audit(db, manager.id, "invoice_reviewed", f"Faktura ID: {invoice.id}; status: {data.status}; izoh: {data.comment or ''}")
    db.commit()
    return {"id": invoice.id, "status": invoice.status}

