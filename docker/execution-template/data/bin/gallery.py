"""
Simple image gallery HTTP server.

Run: python gallery.py /path/to/images --port 8000 --max-size 800

Features:
- Browse subdirectories and view images in a folder.
- Server-side resizing (thumbnails) when Pillow is installed; otherwise CSS-constrained display.
- Caches generated thumbnails under `.gallery_cache` in the served root.
"""

from http.server import SimpleHTTPRequestHandler, HTTPServer
import argparse
import os
import urllib.parse
import posixpath
import io
import mimetypes
from pathlib import Path
import socket

try:
	from PIL import Image
	_PIL_AVAILABLE = True
except Exception:
	Image = None
	_PIL_AVAILABLE = False


IMAGE_EXTS = {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.tiff'}


def is_image_file(path: Path):
	return path.suffix.lower() in IMAGE_EXTS


class GalleryHandler(SimpleHTTPRequestHandler):
	server_version = "qface-gallery/0.1"

	def do_GET(self):
		parsed = urllib.parse.urlsplit(self.path)
		path = parsed.path
		# redirect root to /browse/
		if path in ('', '/'):
			self.send_response(302)
			self.send_header('Location', '/browse/')
			self.end_headers()
			return
		if path.startswith("/browse"):
			# /browse/<relpath>
			rel = path[len('/browse'):]
			if rel.startswith('/'):
				rel = rel[1:]
			rel = urllib.parse.unquote(rel)
			fs_path = (Path(self.server.base_path) / rel).resolve()
			if not fs_path.exists() or not str(fs_path).startswith(str(Path(self.server.base_path).resolve())):
				self.send_error(404, "Not Found")
				return
			if fs_path.is_dir():
				self.send_gallery(fs_path, rel)
				return
			# if it's a file, fall through to normal serving by rewriting path
		elif path.startswith('/thumb'):
			# /thumb/<size>/<relpath>
			parts = path.split('/', 3)
			# ['', 'thumb', '<size>', '<relpath>']
			if len(parts) >= 4:
				try:
					size = int(parts[2])
				except Exception:
					size = int(self.server.max_size) if self.server.max_size else 0
				rel = parts[3]
				rel = urllib.parse.unquote(rel)
				fs_path = (Path(self.server.base_path) / rel).resolve()
				if not fs_path.exists() or not str(fs_path).startswith(str(Path(self.server.base_path).resolve())):
					self.send_error(404, "Not Found")
					return
				if not fs_path.is_file() or not is_image_file(fs_path):
					self.send_error(404, "Not an image")
					return
				if size <= 0:
					# serve original
					return super().do_GET()
				return self.send_thumbnail(fs_path, size)

		# default: let SimpleHTTPRequestHandler handle static files relative to base_path
		return super().do_GET()

	def list_directory(self, path):
		# Override to prevent default directory listing; we use /browse
		self.send_error(403, "Directory listing not supported; use /browse")
		return None

	def translate_path(self, path):
		# Map requested path onto the served base_path filesystem root
		# reuse SimpleHTTPRequestHandler logic but anchored at server.base_path
		path = urllib.parse.urlsplit(path).path
		path = posixpath.normpath(urllib.parse.unquote(path))
		parts = path.lstrip('/').split('/')
		full = Path(self.server.base_path)
		for part in parts:
			if not part:
				continue
			# avoid .. segments
			if part in (os.curdir, os.pardir):
				continue
			full = full / part
		return str(full)

	def send_gallery(self, fs_path: Path, relpath: str):
		# produce a simple HTML page listing subdirs and images
		entries = sorted(fs_path.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()))
		images = [p for p in entries if p.is_file() and is_image_file(p)]
		dirs = [p for p in entries if p.is_dir()]

		title = f"Gallery - /{relpath}" if relpath else "Gallery - /"
		max_size = int(self.server.max_size) if self.server.max_size else 0

		html = [
			'<!doctype html>',
			'<html><head><meta charset="utf-8">',
			f'<title>{title}</title>',
			'<style>',
			'body{font-family:Arial,Helvetica,sans-serif;padding:16px}',
			'.controls{margin-bottom:12px}',
			'.gallery{display:flex;flex-wrap:wrap;gap:12px;align-items:flex-start}',
			'.thumb{width:220px;text-align:center;word-break:break-word}',
			'.thumb img{display:block;border:1px solid #ccc;background:#f8f8f8;max-width:100%;height:auto}',
			'.thumb .name{font-size:12px;margin-top:6px;word-break:break-word;overflow-wrap:anywhere}',
			'</style>',
			'</head><body>',
			f'<h1>{title}</h1>',
			'<div class="controls">',
			f'<a href="/">Root</a>',
			'</div>',
		]

		if dirs:
			html.append('<h2>Folders</h2><ul>')
			for d in dirs:
				subrel = (Path(relpath) / d.name) if relpath else Path(d.name)
				href = '/browse/' + urllib.parse.quote(str(subrel).replace('\\\\', '/'))
				html.append(f'<li><a href="{href}">{d.name}</a></li>')
			html.append('</ul>')

		if images:
			html.append('<h2>Images</h2>')
			html.append('<div class="gallery">')
			for img in images:
				img_rel = (Path(relpath) / img.name) if relpath else Path(img.name)
				img_url = '/'+urllib.parse.quote(str(img_rel).replace('\\\\','/'))
				if max_size > 0 and _PIL_AVAILABLE:
					thumb_url = f'/thumb/{max_size}/' + urllib.parse.quote(str(img_rel).replace('\\\\','/'))
					display_src = thumb_url
				elif max_size > 0 and not _PIL_AVAILABLE:
					# no server-side resizing available; still constrain via CSS
					display_src = img_url
				else:
					display_src = img_url
				img_style = ''
				if max_size > 0:
					img_style = f'style="max-width:{max_size}px;max-height:{max_size}px"'
				html.append('<div class="thumb">')
				html.append(f'<a href="{img_url}" target="_blank"><img src="{display_src}" {img_style} alt="{img.name}"></a>')
				html.append(f'<div class="name">{img.name}</div>')
				html.append('</div>')
			html.append('</div>')

		if not dirs and not images:
			html.append('<p><em>(empty)</em></p>')

		html.append('</body></html>')
		body = '\n'.join(html).encode('utf-8')
		self.send_response(200)
		self.send_header('Content-Type', 'text/html; charset=utf-8')
		self.send_header('Content-Length', str(len(body)))
		self.end_headers()
		self.wfile.write(body)

	def send_thumbnail(self, fs_path: Path, size: int):
		# create or retrieve cached thumbnail
		if not _PIL_AVAILABLE:
			self.send_error(500, 'Pillow not available for thumbnailing')
			return

		rel = fs_path.relative_to(Path(self.server.base_path))
		cache_dir = Path(self.server.base_path) / '.gallery_cache' / str(size) / rel.parent
		cache_dir.mkdir(parents=True, exist_ok=True)
		cached = cache_dir / rel.name
		try:
			if not cached.exists():
				with Image.open(fs_path) as im:
					im.thumbnail((size, size))
					# preserve format
					fmt = im.format or 'JPEG'
					im.save(cached, format=fmt)
			ctype = mimetypes.guess_type(str(cached))[0] or 'application/octet-stream'
			self.send_response(200)
			self.send_header('Content-Type', ctype)
			fs = cached.stat()
			self.send_header('Content-Length', str(fs.st_size))
			self.end_headers()
			with cached.open('rb') as f:
				self.wfile.write(f.read())
		except Exception as e:
			self.send_error(500, f'Error generating thumbnail: {e}')


def run_server(directory: str, host: str, port: int, max_size: int):
	directory = os.path.abspath(directory)
	os.chdir(directory)
	handler = GalleryHandler
	httpd = HTTPServer((host, port), handler)
	httpd.base_path = directory
	httpd.max_size = int(max_size) if max_size else 0
	# attempt to discover a usable local IP for convenience
	local_ip = None
	try:
		s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
		s.connect(("8.8.8.8", 80))
		local_ip = s.getsockname()[0]
		s.close()
	except Exception:
		local_ip = None

	print(f"Serving {directory} on http://{host}:{port}/ (max_size={httpd.max_size}, pillow={_PIL_AVAILABLE})")
	if host == '0.0.0.0' and local_ip:
		print(f"Accessible on your LAN at: http://{local_ip}:{port}/")
	try:
		httpd.serve_forever()
	except KeyboardInterrupt:
		print('\nStopping server')
		httpd.server_close()


def main():
	p = argparse.ArgumentParser(description='Simple image gallery HTTP server')
	p.add_argument('directory', nargs='?', default='.', help='Directory to serve')
	p.add_argument('--host', default='127.0.0.1', help='Host to bind to')
	p.add_argument('--port', type=int, default=8000, help='Port')
	p.add_argument('--max-size', type=int, default=800, help='Max dimension (px) for thumbnails; 0 for original')
	args = p.parse_args()
	run_server(args.directory, args.host, args.port, args.max_size)


if __name__ == '__main__':
	main()


