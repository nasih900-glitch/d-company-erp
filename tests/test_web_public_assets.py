from io import BytesIO
from email.message import Message
import sys
from unittest import TestCase
from unittest.mock import patch
from urllib.error import HTTPError

from ops import verify_web_public_assets as check


class Response(BytesIO):
    status = 200

    def __init__(self, body: bytes, content_type: str):
        super().__init__(body)
        self.headers = Message()
        self.headers['Content-Type'] = content_type


class PublicAssetDeliveryTest(TestCase):
    def test_real_source_bytes_are_required_for_each_public_asset(self):
        def opened(request, timeout):
            relative = request.full_url.removeprefix('http://ci-runtime.local/')
            self.assertEqual(timeout, 15)
            self.assertEqual(request.get_header('Host'), 'ci-runtime.local')
            return Response((check.ROOT / 'frontend/public' / relative).read_bytes(), sorted(check.ASSETS[relative])[0])
        with patch.object(check, 'open_url', side_effect=opened) as network:
            check.verify('http://ci-runtime.local', 'ci-runtime.local')
        self.assertEqual(network.call_count, len(check.ASSETS))

    def test_nginx_permission_denial_is_a_failure(self):
        with patch.object(check, 'open_url', side_effect=HTTPError('http://ci-runtime.local/brand/dcompany-app-mark.png', 403, 'Forbidden', {}, None)):
            with self.assertRaises(HTTPError):
                check.verify('http://ci-runtime.local')

    def test_spa_fallback_corrupt_or_wrong_type_assets_are_failures(self):
        for content_type in ('text/html', 'image/png'):
            with self.subTest(content_type=content_type), patch.object(check, 'open_url', return_value=Response(b'<html>SPA fallback</html>', content_type)):
                with self.assertRaises(RuntimeError):
                    check.verify('http://ci-runtime.local')

    def test_redirect_handler_refuses_a_second_destination(self):
        request = check.Request('http://ci-runtime.local/brand/dcompany-app-mark.png')
        self.assertIsNone(check.NoRedirect().redirect_request(request, None, 302, 'Found', {}, 'https://other.example/mark.png'))

    def test_exact_manifest_bytes_with_wrong_mime_are_refused_by_both_transports(self):
        self.assertEqual(check.ASSETS['manifest.webmanifest'], {'application/manifest+json'})
        for wrong_type in ('application/octet-stream', 'application/json'):
            def asset(relative):
                body = (check.ROOT / 'frontend/public' / relative).read_bytes()
                content_type = wrong_type if relative == 'manifest.webmanifest' else sorted(check.ASSETS[relative])[0]
                return content_type, body

            def opened(request, timeout):
                relative = request.full_url.removeprefix('http://ci-runtime.local/')
                content_type, body = asset(relative)
                return Response(body, content_type)

            def container_http(command, request, limit):
                relative = request.split(b' ', 2)[1].decode('ascii').removeprefix('/')
                content_type, body = asset(relative)
                return ContainerPublicAssetDeliveryTest.response(body, content_type.encode('ascii'))

            with self.subTest(transport='url', mime=wrong_type), patch.object(check, 'open_url', side_effect=opened):
                with self.assertRaisesRegex(RuntimeError, 'manifest.webmanifest.*status/type'):
                    check.verify('http://ci-runtime.local')
            with self.subTest(transport='container', mime=wrong_type), patch.object(check, '_bounded_command', side_effect=container_http):
                with self.assertRaisesRegex(RuntimeError, 'manifest.webmanifest.*status/type'):
                    check.verify(container='a' * 64)


class ContainerPublicAssetDeliveryTest(TestCase):
    @staticmethod
    def response(body=b'png', content_type=b'image/png'):
        return b'HTTP/1.1 200 OK\r\nContent-Type: ' + content_type + b'\r\nContent-Length: ' + str(len(body)).encode() + b'\r\nConnection: close\r\n\r\n' + body

    def test_container_transport_uses_only_fixed_exec_arguments_and_local_http(self):
        container = 'a' * 64
        with patch.object(check, '_bounded_command', return_value=self.response()) as command:
            self.assertEqual(check._container_response(container, 'brand/dcompany-app-mark.png', 3), (200, 'image/png', b'png'))
        args, request, limit = command.call_args.args
        self.assertEqual(args, ['docker', 'exec', '-i', container, 'nc', '-w', '5', '127.0.0.1', '80'])
        self.assertEqual(request, b'GET /brand/dcompany-app-mark.png HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n')
        self.assertEqual(limit, check.MAX_HEADER_BYTES + 4 + 3)

    def test_container_and_asset_arguments_are_validated_before_execution(self):
        with patch.object(check, '_bounded_command') as command:
            for container in ('', 'frontend', 'a' * 12, 'A' * 64, 'a' * 64 + ';id'):
                with self.subTest(container=container), self.assertRaises(ValueError):
                    check._container_response(container, 'icon-192.png', 3)
            for relative in ('../.env', 'icon-192.png?x=1', 'icon-192.png\r\nHost: other'):
                with self.subTest(relative=relative), self.assertRaises(ValueError):
                    check._container_response('a' * 64, relative, 3)
        command.assert_not_called()

    def test_parser_refuses_redirect_error_or_multiple_http_responses(self):
        for status in (b'301 Moved', b'302 Found', b'403 Forbidden', b'100 Continue'):
            raw = self.response().replace(b'200 OK', status)
            with self.subTest(status=status), self.assertRaises(RuntimeError):
                check._parse_container_response(raw, 3)
        with self.assertRaises(RuntimeError):
            check._parse_container_response(self.response() + self.response(), 3)

    def test_parser_refuses_ambiguous_framing_and_oversized_headers(self):
        valid = self.response()
        bad = [
            valid.replace(b'Content-Length: 3', b'Content-Length: 3\r\nContent-Length: 3'),
            valid.replace(b'Content-Type: image/png', b'Content-Type: image/png\r\nContent-Type: text/html'),
            valid.replace(b'Content-Length: 3', b'Content-Length: 4'),
            valid.replace(b'Connection: close', b'Transfer-Encoding: chunked'),
            valid.replace(b'Connection: close', b'Content-Encoding: gzip'),
            valid.replace(b'Connection: close', b'X-Padding: ' + b'x' * check.MAX_HEADER_BYTES),
            valid.replace(b'Connection: close', b'Bad Header: value'),
            b'HTTP/1.1 200 OK\r\n',
        ]
        for raw in bad:
            with self.subTest(raw_prefix=raw[:80]), self.assertRaises(RuntimeError):
                check._parse_container_response(raw, 3)

    def test_container_transport_reuses_the_source_hash_and_mime_checks(self):
        def response(container, relative, size):
            self.assertEqual(container, 'a' * 64)
            body = (check.ROOT / 'frontend/public' / relative).read_bytes()
            self.assertEqual(len(body), size)
            return 200, sorted(check.ASSETS[relative])[0], body
        with patch.object(check, '_container_response', side_effect=response) as transport:
            check.verify(container='a' * 64)
        self.assertEqual(transport.call_count, len(check.ASSETS))
        for result in ((200, 'image/png', b'wrong bytes'), (200, 'text/html', b'wrong bytes')):
            with self.subTest(result=result), patch.object(check, '_container_response', return_value=result), self.assertRaises(RuntimeError):
                check.verify(container='a' * 64)

    def test_transport_choices_cannot_be_combined_or_redirected_with_host(self):
        for kwargs in ({}, {'base_url': 'http://local', 'container': 'a' * 64}, {'container': 'a' * 64, 'host': 'other'}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                check.verify(**kwargs)

    def test_process_stdout_stderr_and_time_are_bounded(self):
        for script in ('import sys;sys.stdout.buffer.write(b"x"*20)', 'import sys;sys.stderr.buffer.write(b"x"*9000)'):
            with self.subTest(script=script), self.assertRaisesRegex(RuntimeError, 'size bound'):
                check._bounded_command([sys.executable, '-c', script], b'', 8)
        with patch.object(check, 'CONTAINER_TIMEOUT_SECONDS', 0.05), self.assertRaisesRegex(RuntimeError, 'timed out|transport failed'):
            check._bounded_command([sys.executable, '-c', 'import time;time.sleep(10)'], b'', 8)

    def test_process_failure_is_refused_without_exposing_stderr(self):
        with self.assertRaisesRegex(RuntimeError, '^Public asset container HTTP transport failed$'):
            check._bounded_command([sys.executable, '-c', 'import sys;sys.stderr.write("private diagnostic");sys.exit(1)'], b'', 8)
