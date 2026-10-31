"""ASGI entry point: `uvicorn slopmatica_cloud.main:app` (reads SLOPMATICA_* env vars)."""

from .app import create_app

app = create_app()
