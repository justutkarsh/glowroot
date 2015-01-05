/*
 * Copyright 2012-2015 the original author or authors.
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

glowroot.config([
  '$provide',
  '$stateProvider',
  '$urlRouterProvider',
  function ($provide, $stateProvider, $urlRouterProvider) {
    var waitForLayout;
    if (!window.layout) {
      // some controllers need to wait for layout when running under grunt serve
      waitForLayout = {
        dummy: ['$q', '$rootScope', function ($q, $rootScope) {
          var deferred = $q.defer();
          var unregisterWatch = $rootScope.$watch('layout', function (value) {
            if (value) {
              deferred.resolve();
              unregisterWatch();
            }
          });
          return deferred.promise;
        }]
      };
    }
    // overriding autoscroll=true behavior to scroll to the top of the page
    $provide.decorator('$uiViewScroll', [
      function () {
        return function () {
          window.scrollTo(0, 0);
        };
      }
    ]);
    $urlRouterProvider.otherwise('performance');
    $stateProvider.state('/performance', {
      url: '/performance?transaction-type',
      templateUrl: 'views/performance.html',
      controller: 'PerformanceCtrl',
      // performance controller needs to wait for layout when running under grunt serve
      resolve: waitForLayout
    });
    $stateProvider.state('performance-flame-graph', {
      url: '/performance/flame-graph',
      templateUrl: 'views/performance-flame-graph.html',
      controller: 'PerformanceFlameGraphCtrl',
      resolve: {
        dummy: ['$q', '$timeout', function ($q, $timeout) {
          var deferred = $q.defer();
          function checkForD3() {
            if (window.d3) {
              deferred.resolve();
            } else {
              $timeout(checkForD3, 100);
            }
          }
          $timeout(checkForD3, 100);
          return deferred.promise;
        }]
      }
    });
    $stateProvider.state('errors', {
      url: '/errors?transaction-type',
      templateUrl: 'views/errors.html',
      controller: 'ErrorsCtrl',
      // errors controller needs to wait for layout when running under grunt serve
      resolve: waitForLayout
    });
    $stateProvider.state('traces', {
      url: '/traces',
      templateUrl: 'views/traces.html',
      controller: 'TracesCtrl',
      // traces controller needs to wait for layout when running under grunt serve
      resolve: waitForLayout
    });
    $stateProvider.state('jvm', {
      url: '/jvm',
      templateUrl: 'views/jvm.html',
      controller: 'JvmCtrl'
    });
    $stateProvider.state('jvm.gauges', {
      url: '/gauges',
      templateUrl: 'views/jvm/gauges.html',
      controller: 'JvmGaugesCtrl',
      // gauges controller needs to wait for layout when running under grunt serve
      resolve: waitForLayout
    });
    $stateProvider.state('jvm.mbeanTree', {
      url: '/mbean-tree',
      templateUrl: 'views/jvm/mbean-tree.html',
      controller: 'JvmMBeanTreeCtrl'
    });
    $stateProvider.state('jvm.threadDump', {
      url: '/thread-dump',
      templateUrl: 'views/jvm/thread-dump.html',
      controller: 'JvmThreadDumpCtrl'
    });
    $stateProvider.state('jvm.heapDump', {
      url: '/heap-dump',
      templateUrl: 'views/jvm/heap-dump.html',
      controller: 'JvmHeapDumpCtrl'
    });
    $stateProvider.state('jvm.processInfo', {
      url: '/process-info',
      templateUrl: 'views/jvm/process-info.html',
      controller: 'JvmProcessInfoCtrl'
    });
    $stateProvider.state('jvm.systemProperties', {
      url: '/system-properties',
      templateUrl: 'views/jvm/system-properties.html',
      controller: 'JvmSystemPropertiesCtrl'
    });
    $stateProvider.state('jvm.capabilities', {
      url: '/capabilities',
      templateUrl: 'views/jvm/capabilities.html',
      controller: 'JvmCapabilitiesCtrl'
    });
    $stateProvider.state('config', {
      url: '/config',
      templateUrl: 'views/config.html',
      controller: 'ConfigCtrl'
    });
    $stateProvider.state('config.traces', {
      url: '/traces',
      templateUrl: 'views/config/traces.html',
      controller: 'ConfigCommonCtrl',
      resolve: {
        backendUrl: function () {
          return 'backend/config/trace';
        }
      }
    });
    $stateProvider.state('config.profiling', {
      url: '/profiling',
      templateUrl: 'views/config/profiling.html',
      controller: 'ConfigCommonCtrl',
      resolve: {
        backendUrl: function () {
          return 'backend/config/profiling';
        }
      }
    });
    $stateProvider.state('config.userRecording', {
      url: '/user-recording',
      templateUrl: 'views/config/user-recording.html',
      controller: 'ConfigCommonCtrl',
      resolve: {
        backendUrl: function () {
          return 'backend/config/user-recording';
        }
      }
    });
    $stateProvider.state('config.capturePoints', {
      url: '/capture-points',
      templateUrl: 'views/config/capture-point-list.html',
      controller: 'ConfigCapturePointListCtrl'
    });
    $stateProvider.state('config.gauges', {
      url: '/gauges',
      templateUrl: 'views/config/gauge-list.html',
      controller: 'ConfigGaugeListCtrl'
    });
    $stateProvider.state('config.storage', {
      url: '/storage',
      templateUrl: 'views/config/storage.html',
      controller: 'ConfigStorageCtrl'
    });
    $stateProvider.state('config.userInterface', {
      url: '/user-interface',
      templateUrl: 'views/config/user-interface.html',
      controller: 'ConfigUserInterfaceCtrl'
    });
    $stateProvider.state('config.advanced', {
      url: '/advanced',
      templateUrl: 'views/config/advanced.html',
      controller: 'ConfigCommonCtrl',
      resolve: {
        backendUrl: function () {
          return 'backend/config/advanced';
        }
      }
    });
    $stateProvider.state('config.plugin', {
      url: '/plugin/:pluginId',
      templateUrl: 'views/config/plugin.html',
      controller: 'ConfigPluginCtrl'
    });
    $stateProvider.state('login', {
      url: '/login',
      templateUrl: 'views/login.html',
      controller: 'LoginCtrl'
    });
  }
]);
