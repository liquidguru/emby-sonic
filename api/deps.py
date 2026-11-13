from typing import Annotated
from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession
from db.database import get_db
from api.auth import verify_emby_admin, verify_emby_token, verify_worker

DB = Annotated[AsyncSession, Depends(get_db)]
AuthToken = Annotated[str, Depends(verify_emby_token)]
# Library maintenance: an Emby administrator or the server API key only.
AdminToken = Annotated[str, Depends(verify_emby_admin)]
WorkerAuth = Annotated[str, Depends(verify_worker)]
