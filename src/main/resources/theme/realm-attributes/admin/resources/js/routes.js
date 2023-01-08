module.config(['$routeProvider', function ($routeProvider) {
    $routeProvider
        .when('/realms/:realm/realm-attributes', {
            templateUrl: resourceUrl + '/partials/realm-attributes.html',
            resolve: {
                realm: function (RealmLoader) {
                    return RealmLoader();
                },
                serverInfo: function (ServerInfo) {
                    return ServerInfo.delay;
                }
            },
            controller: 'RealmAttributesCtrl'
        })
}]);