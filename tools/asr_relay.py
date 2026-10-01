#!/usr/bin/env python3
"""把局域网 8738 转发到本机 127.0.0.1:8737 的 FunASR 转写服务。

为什么要这一层：fchat 那边的 funasr_server.py 明确只绑回环（代码里写着
"只绑回环：容器经 host.docker.internal 访问"），而词典笔是另一台设备，
要访问它就得开局域网口。改别人的服务绑定口径不如加一层可关掉的转发。

只用标准库，随起随停：Ctrl-C 或 kill 掉进程即可，不留痕迹。
"""
import argparse
import select
import socket
import threading


def pipe(src, dst):
    while True:
        try:
            r, _, _ = select.select([src], [], [], 60)
            if not r:
                break
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
        except OSError:
            break
    for s in (src, dst):
        try:
            s.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def handle(conn, target):
    try:
        up = socket.create_connection(target, timeout=5)
    except OSError:
        conn.close()
        return
    t1 = threading.Thread(target=pipe, args=(conn, up), daemon=True)
    t2 = threading.Thread(target=pipe, args=(up, conn), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    conn.close()
    up.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen-port", type=int, default=8738)
    ap.add_argument("--target", default="127.0.0.1:8737")
    args = ap.parse_args()
    host, port = args.target.split(":")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.listen_port))
    srv.listen(16)
    print(f"ASR relay 0.0.0.0:{args.listen_port} -> {args.target}", flush=True)
    while True:
        conn, addr = srv.accept()
        print(f"  + {addr[0]}:{addr[1]}", flush=True)
        threading.Thread(target=handle, args=(conn, (host, int(port))), daemon=True).start()


if __name__ == "__main__":
    main()
