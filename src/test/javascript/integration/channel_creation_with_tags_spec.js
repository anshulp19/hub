require('../integration_config');
const {
    fromObjectPath,
    getProp,
} = require('../lib/helpers');

var channelName = utils.randomChannelName();
var channelResource = channelUrl + '/' + channelName;

describe(__filename, function () {
    it('verifies the channel doesn\'t exist yet', function (done) {
        utils.httpGet(channelResource)
            .then(function (response) {
                expect(getProp('statusCode', response)).toEqual(404);
            })
            .finally(done);
    });

    it('creates a channel with tags', function (done) {
        var url = channelUrl;
        var headers = {'Content-Type': 'application/json'};
        var body = {'name': channelName, 'tags': ['foo-bar', 'bar', 'tag:z']};

        utils.httpPost(url, headers, body)
            .then(function (response) {
                const contentType = fromObjectPath(['headers', 'content-type'], response);
                const tags = fromObjectPath(['body', 'tags'], response);
                expect(getProp('statusCode', response)).toEqual(201);
                expect(contentType).toEqual('application/json');
                expect(tags).toEqual(['bar', 'foo-bar', 'tag:z']);
            })
            .finally(done);
    });

    it('verifies the channel does exist', function (done) {
        utils.httpGet(channelResource)
            .then(function (response) {
                const contentType = fromObjectPath(['headers', 'content-type'], response);
                const tags = fromObjectPath(['body', 'tags'], response);
                const name = fromObjectPath(['body', 'name'], response);
                expect(getProp('statusCode', response)).toEqual(200);
                expect(contentType).toEqual('application/json');
                expect(name).toEqual(channelName);
                expect(tags).toEqual(['bar', 'foo-bar', 'tag:z']);
            })
            .finally(done);
    });
});
