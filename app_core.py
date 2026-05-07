import os
import configparser
from flask import Flask, jsonify, request


def create_app():
    app = Flask(__name__)

    @app.route('/', methods=['GET'])
    def index():
        return jsonify({
            'message': 'hao-for-you Python server is running',
            'status': 'ok'
        })

    @app.route('/health', methods=['GET'])
    def health():
        return jsonify({'status': 'ok'})

    @app.route('/echo', methods=['POST'])
    def echo():
        payload = request.get_json(silent=True)
        if payload is None:
            return jsonify({'error': 'Missing JSON body'}), 400

        return jsonify({
            'received': payload,
            'message': 'Echo from hao-for-you server'
        })

    return app


app = create_app()


if __name__ == '__main__':
    # 讀取 config.ini 設定
    config = configparser.ConfigParser()
    config.read('config.ini')

    # 從環境變數或 config.ini 取得設定，環境變數優先
    port = int(os.environ.get('PORT', config.get('server', 'port', fallback='5000')))
    host = os.environ.get('HOST', config.get('server', 'host', fallback='0.0.0.0'))
    debug_str = os.environ.get('DEBUG', config.get('server', 'debug', fallback='false'))
    debug = debug_str.lower() in ('1', 'true', 'yes')

    app.run(host=host, port=port, debug=debug)
