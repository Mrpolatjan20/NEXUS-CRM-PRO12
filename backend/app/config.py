
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "NEXUS CRM PRO"
    database_url: str = "sqlite:///./nexus_crm.db"
    secret_key: str = "CHANGE_THIS_SECRET_KEY_BEFORE_DEPLOY"
    access_token_minutes: int = 60
    admin_reset_key: str = ""

    model_config = SettingsConfigDict(
        env_file=".env",
        extra="ignore"
    )


settings = Settings()
