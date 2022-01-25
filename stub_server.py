from wsgiref.simple_server import make_server

import falcon
import time

counter = 0

class TestResource:

    def on_get(self, req: falcon.request.Request, resp: falcon.response.Response):
        """Handles GET requests"""
        # 4101 is the first prayer, toggle it to send data to the server
        resp.media = {
            'varbits': [0, 100, 9657, 4101, 4102, 4103, 5000, 10000, 4104],
            'varps': [1, 3, 5, 6, 7, 10],
            'version': 5
        }
        resp.status = falcon.HTTP_200
        return resp

    def on_post(self, req, resp):
        global counter
        print(req.media)
        if counter % 2 == 0:
            resp.status = falcon.HTTP_200
        else:
            time.sleep(6)
            resp.status = falcon.HTTP_400
        counter += 1
        return resp

    def on_get_check(self, req, resp):
        resp.media = {
            'version': 5
        }
        resp.status = falcon.HTTP_200
        return resp


def create_app():
    app = falcon.App()
    # Resources are represented by long-lived class instances
    t = TestResource()
    app.add_route('/manifest', t)
    app.add_route('/version', t, suffix='check')
    app.add_route('/submit', t)
    return app


if __name__ == '__main__':
    with make_server('', 8000, create_app()) as httpd:
        print(f'Serving on port 8000...')

        # Serve until process is killed
        httpd.serve_forever()
