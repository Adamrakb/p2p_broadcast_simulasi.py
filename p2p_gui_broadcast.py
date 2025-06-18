# Versi dengan Hasil Pencarian Langsung dan Recheck

from flask import Flask, request, render_template_string, redirect, send_from_directory, url_for
import os, threading, socket, json, time

app = Flask(__name__)

HOSTNAME = socket.gethostbyname(socket.gethostname())
PORT = 5000
PEER_PORT = 6000
SHARE_DIR = f'shared_{PEER_PORT}'
PEERS = set()
FILES = set()
TTL = 4

search_results = {}

if not os.path.exists(SHARE_DIR):
    os.makedirs(SHARE_DIR)

HTML = '''
<h1>Peer-to-Peer Node @ {{host}}</h1>
<form method="post" enctype="multipart/form-data" action="/upload">
  <input type="file" name="file">
  <button type="submit">Upload</button>
</form>

<form method="get" action="/search">
  <input name="filename" placeholder="Search file">
  <button type="submit">Search</button>
</form>

<h2>Files in this peer:</h2>
<ul>
{% for f in files %}
  <li><a href="/download/{{f}}">{{f}}</a></li>
{% endfor %}
</ul>

<h2>Search Results:</h2>
<ul>
{% for f, info in results.items() %}
  <li><strong>{{f}}</strong> found at {{info['host']}}:<a href="http://{{info['host']}}:5000/download/{{f}}">Download</a></li>
{% else %}
  <li>No results yet. <a href="/recheck">Recheck</a></li>
{% endfor %}
</ul>
'''

@app.route('/')
def home():
    return render_template_string(HTML, files=os.listdir(SHARE_DIR), host=HOSTNAME, results=search_results)

@app.route('/upload', methods=['POST'])
def upload():
    f = request.files['file']
    path = os.path.join(SHARE_DIR, f.filename)
    f.save(path)
    FILES.add(f.filename)
    return redirect('/')

@app.route('/search')
def search():
    filename = request.args.get('filename')
    if not filename:
        return redirect('/')
    if filename in os.listdir(SHARE_DIR):
        search_results[filename] = {"host": HOSTNAME}
    else:
        search_results[filename] = None
        broadcast_search(filename, TTL)
    return redirect('/')

@app.route('/recheck')
def recheck():
    return redirect('/')

@app.route('/download/<filename>')
def download(filename):
    return send_from_directory(SHARE_DIR, filename)

# PEER LISTENER

def peer_listener():
    s = socket.socket()
    s.bind((HOSTNAME, PEER_PORT))
    s.listen()
    while True:
        conn, addr = s.accept()
        data = conn.recv(1024).decode()
        try:
            message = json.loads(data)
            if message['type'] == 'SEARCH':
                handle_search(message, addr)
            elif message['type'] == 'FOUND':
                search_results[message['filename']] = {"host": message['host']}
        except:
            pass
        conn.close()

# HANDLE SEARCH AND BROADCAST

def handle_search(message, addr):
    filename = message['filename']
    ttl = message['ttl']

    if filename in os.listdir(SHARE_DIR):
        reply = json.dumps({"type": "FOUND", "filename": filename, "host": HOSTNAME})
        try:
            s = socket.socket()
            s.connect((addr[0], PEER_PORT))
            s.send(reply.encode())
            s.close()
        except:
            pass
    elif ttl > 1:
        broadcast_search(filename, ttl - 1)

def broadcast_search(filename, ttl):
    for peer in PEERS:
        try:
            s = socket.socket()
            s.connect((peer, PEER_PORT))
            msg = json.dumps({"type": "SEARCH", "filename": filename, "ttl": ttl})
            s.send(msg.encode())
            s.close()
        except:
            continue

# DISCOVERY

def discover_peers():
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    udp.sendto(b'DISCOVER_PEER', ('<broadcast>', 6001))
    udp.close()

def peer_discovery_listener():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(('', 6001))
    while True:
        data, addr = s.recvfrom(1024)
        if data == b'DISCOVER_PEER':
            if addr[0] != HOSTNAME:
                PEERS.add(addr[0])

if __name__ == '__main__':
    threading.Thread(target=peer_listener, daemon=True).start()
    threading.Thread(target=peer_discovery_listener, daemon=True).start()
    threading.Thread(target=discover_peers, daemon=True).start()
    app.run(host='0.0.0.0', port=PORT)
