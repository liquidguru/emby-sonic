from typing import Annotated
from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession
from db.database import get_db
from api.auth import verify_emby_token

DB = Annotated[AsyncSession, Depends(get_db)]
AuthToken = Annotated[str, Depends(verify_emby_token)]
