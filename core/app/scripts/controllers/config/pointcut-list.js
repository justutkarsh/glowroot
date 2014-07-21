/*
 * Copyright 2012-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global glowroot */

glowroot.controller('ConfigPointcutListCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'httpErrors',
  function ($scope, $http, $timeout, httpErrors) {
    // initialize page binding object
    $scope.page = {};

    $http.get('backend/config/pointcut')
        .success(function (data) {
          $scope.loaded = true;
          $scope.pointcuts = [];
          for (var i = 0; i < data.configs.length; i++) {
            $scope.pointcuts.push({
              config: data.configs[i]
            });
          }
          // use object so dirty flag can be updated by child controllers
          $scope.page.dirty = data.jvmOutOfSync;
          $scope.jvmRetransformClassesSupported = data.jvmRetransformClassesSupported;
          // preload cache for class name and method name auto completion
          $http.get('backend/config/preload-classpath-cache');
        })
        .error(httpErrors.handler($scope));

    $scope.addPointcut = function () {
      $scope.pointcuts.push({
        config: {
          adviceKind: 'metric'
        }
      });
    };

    // this is called by child controller
    $scope.removePointcut = function (pointcut) {
      var index = $scope.pointcuts.indexOf(pointcut);
      if (index !== -1) {
        $scope.pointcuts.splice(index, 1);
      }
    };

    $scope.retransformClasses = function (deferred) {
      $http.post('backend/admin/reweave-pointcuts', '')
          .success(function (data) {
            $scope.page.dirty = false;
            if (data.classes) {
              deferred.resolve('Success (re-transformed ' + data.classes + ' class' + (data.classes > 1 ? 'es' : '') +
                  ')');
            } else {
              deferred.resolve('Success (no classes needed re-transforming)');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };
  }
]);
