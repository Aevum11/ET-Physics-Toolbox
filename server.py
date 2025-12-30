import os
import datetime
import zipfile
from flask import Flask, request, jsonify
from werkzeug.utils import secure_filename
# Security: Rate Limiting (Requires: pip install Flask-Limiter)
# from flask_limiter import Limiter
# from flask_limiter.util import get_remote_address

# --- CONFIGURATION ---
HOST = '0.0.0.0'
PORT = 5000
UPLOAD_FOLDER = 'received_reports'
ALLOWED_EXTENSIONS = {'zip', 'csv'} 

# SECURITY: Load API Key from Environment
API_KEY = os.environ.get("ET_API_KEY", "CHANGE_ME_IN_PROD") 

app = Flask(__name__)
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024  # 16MB Limit

# Setup Limiter (Stub - Uncomment if Flask-Limiter installed)
# limiter = Limiter(get_remote_address, app=app, default_limits=["200 per day", "50 per hour"])

if not os.path.exists(UPLOAD_FOLDER):
    os.makedirs(UPLOAD_FOLDER)

def allowed_file(filename):
    return '.' in filename and \
           filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

def check_auth(request):
    token = request.headers.get('X-ET-AUTH-TOKEN')
    return token == API_KEY

@app.route('/api/v1/status', methods=['GET'])
def status():
    return jsonify({"status": "online", "server": "ET-Diagnostic-Node-v9"}), 200

@app.route('/api/v1/upload', methods=['POST'])
# @limiter.limit("10 per minute") # Rate limit uploads
def upload_file():
    if not check_auth(request):
        return jsonify({"error": "Unauthorized"}), 401

    if 'file' not in request.files:
        return jsonify({"error": "No file part"}), 400
    
    file = request.files['file']
    description = request.form.get('description', '')

    if file.filename == '':
        return jsonify({"error": "No selected file"}), 400
    
    if file and allowed_file(file.filename):
        # Organize by Date
        date_str = datetime.datetime.now().strftime("%Y-%m-%d")
        daily_folder = os.path.join(app.config['UPLOAD_FOLDER'], date_str)
        if not os.path.exists(daily_folder):
            os.makedirs(daily_folder)

        # Save File Securely
        filename = secure_filename(file.filename)
        timestamp = datetime.datetime.now().strftime("%H%M%S")
        save_path = os.path.join(daily_folder, f"{timestamp}_{filename}")
        
        file.save(save_path)
        
        # If ZIP, verify integrity (Optional)
        if filename.endswith('.zip'):
            try:
                with zipfile.ZipFile(save_path, 'r') as zip_ref:
                    if zip_ref.testzip() is not None:
                         return jsonify({"error": "Corrupted ZIP"}), 400
            except:
                return jsonify({"error": "Invalid ZIP"}), 400

        if description:
            desc_path = os.path.join(daily_folder, f"{timestamp}_desc.txt")
            with open(desc_path, "w", encoding="utf-8") as f:
                f.write(description)
        
        print(f"[SUCCESS] Report received: {save_path}")
        return jsonify({"message": "Secure upload successful"}), 201

    return jsonify({"error": "Invalid file type"}), 400

if __name__ == '__main__':
    print(f"[*] ET Secure Server v9.0. API Key Active: {'YES' if API_KEY != 'CHANGE_ME_IN_PROD' else 'NO'}")
    
    # HTTPS CONFIGURATION
    # For production, use a reverse proxy (Nginx) with Let's Encrypt.
    # For local secure testing, generate self-signed certs:
    # openssl req -x509 -newkey rsa:4096 -nodes -out cert.pem -keyout key.pem -days 365
    
    if os.path.exists('cert.pem') and os.path.exists('key.pem'):
        print("[*] SSL Context Loaded. Serving HTTPS.")
        app.run(host=HOST, port=PORT, ssl_context=('cert.pem', 'key.pem'))
    else:
        print("[!] WARNING: SSL Certs not found. Serving HTTP (Insecure).")
        app.run(host=HOST, port=PORT)