#!/usr/bin/env python3
"""ModBridge testbed RCON client: rcon.py <port> <password> <cmd> [cmd...]"""
import socket, struct, sys, time

def pkt(rid, ptype, body):
    data = struct.pack('<ii', rid, ptype) + body.encode('utf8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data

def read_pkt(s):
    raw = b''
    while len(raw) < 4:
        chunk = s.recv(4 - len(raw))
        if not chunk:
            raise ConnectionError('rcon connection closed')
        raw += chunk
    (ln,) = struct.unpack('<i', raw)
    data = b''
    while len(data) < ln:
        chunk = s.recv(ln - len(data))
        if not chunk:
            break
        data += chunk
    rid, ptype = struct.unpack('<ii', data[:8])
    return rid, ptype, data[8:-2].decode('utf8', 'replace')

def main():
    port, pw = int(sys.argv[1]), sys.argv[2]
    cmds = sys.argv[3:]
    s = socket.create_connection(('127.0.0.1', port), timeout=30)
    s.sendall(pkt(1, 3, pw))
    rid, _, _ = read_pkt(s)
    if rid == -1:
        print('AUTH FAILED')
        sys.exit(1)
    out = []
    for c in cmds:
        s.sendall(pkt(2, 2, c))
        s.settimeout(4.0)
        time.sleep(0.4)
        body = ''
        try:
            while True:
                _, _, b = read_pkt(s)
                body += b
                s.settimeout(1.2)
        except (socket.timeout, TimeoutError):
            pass
        out.append(body)
    s.close()
    print('\n'.join(out))

if __name__ == '__main__':
    main()
