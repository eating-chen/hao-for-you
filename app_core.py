import os
import configparser
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


def create_app() -> FastAPI:
    app = FastAPI()

    @app.get('/')
    def index():
        return {
            'message': 'hao-for-you Python server is running',
            'status': 'ok'
        }

    @app.get('/health')
    def health():
        return {'status': 'ok'}

    @app.post('/echo')
    async def echo(request: Request):
        try:
            payload = await request.json()
        except Exception:
            return JSONResponse({'error': 'Missing JSON body'}, status_code=400)

        return {
            'received': payload,
            'message': 'Echo from hao-for-you server'
        }

    return app


app = create_app()


if __name__ == '__main__':
    import uvicorn

    config = configparser.ConfigParser()
    config.read('config.ini')

    port = int(os.environ.get('PORT', config.get('server', 'port', fallback='8008')))
    host = os.environ.get('HOST', config.get('server', 'host', fallback='0.0.0.0'))
    debug_str = os.environ.get('DEBUG', config.get('server', 'debug', fallback='false'))
    reload = debug_str.lower() in ('1', 'true', 'yes')

    uvicorn.run('app_core:app', host=host, port=port, reload=reload)
