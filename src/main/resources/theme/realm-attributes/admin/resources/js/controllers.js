module.controller('RealmAttributesCtrl', function ($scope, Current, Realm, realm, serverInfo, $http, $route, Dialog, Notifications) {
    // Use generic realm update (from base theme)
    genericRealmUpdate($scope, Current, Realm, realm, serverInfo, $http, $route, Dialog, Notifications, "/realms/" + realm.realm + "/theme-attributes");

    $scope.addAttribute = function () {
        $scope.realm.attributes[$scope.newAttribute.key] = $scope.newAttribute.value;
        delete $scope.newAttribute;
    }

    $scope.removeAttribute = function (key) {
        delete $scope.realm.attributes[key];
    }
});