import os
import secrets

from fastapi import Header, HTTPException, status


def verify_internal_token(x_internal_token: str = Header(None)) -> str:
    expected_token = os.environ.get("ML_INTERNAL_TOKEN")
    if not expected_token:
        # If environment variable not set, deny all requests safely
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Unauthorized",
        )
    try:
        token_matches = x_internal_token is not None and secrets.compare_digest(
            x_internal_token.encode("utf-8"), expected_token.encode("utf-8")
        )
    except UnicodeEncodeError:
        token_matches = False
    if not token_matches:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Unauthorized",
        )
    return x_internal_token
