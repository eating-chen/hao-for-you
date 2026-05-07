import os
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
    port = int(os.environ.get('PORT', 5000))
    host = os.environ.get('HOST', '0.0.0.0')
    debug = os.environ.get('DEBUG', 'false').lower() in ('1', 'true', 'yes')

    app.run(host=host, port=port, debug=debug)
