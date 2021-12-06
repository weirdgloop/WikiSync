from wsgiref.simple_server import make_server

import falcon


class TestResource:

    def on_get(self, req: falcon.request.Request, resp: falcon.response.Response):
        """Handles GET requests"""
        # 4101 is the first prayer, toggle it to send data to the server
        resp.media = {
            'varbits': [0, 100, 9657, 4101],
            'varps': [1, 3, 5, 6]
        }
        resp.status = falcon.HTTP_200
        return resp

    def on_post(self, req, resp):
        print(req.media)
        resp.status = falcon.HTTP_200


def create_app():
    app = falcon.App()
    # Resources are represented by long-lived class instances
    t = TestResource()
    app.add_route('/manifest', t)
    app.add_route('/change', t)
    return app


if __name__ == '__main__':
    with make_server('', 8000, create_app()) as httpd:
        print(f'Serving on port 8000...')

        # Serve until process is killed
        httpd.serve_forever()