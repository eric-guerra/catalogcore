angular.module('storefront', ['ngSanitize'])
  .config(['$locationProvider', function ($locationProvider) {
    $locationProvider.html5Mode({ enabled: true, requireBase: false });
  }])
  .controller('SearchCtrl', ['$http', '$location', function ($http, $location) {
    var vm = this;

    // Search state lives in the URL so results are shareable and the back button works.
    function readState() {
      var s = $location.search();
      var attrs = {};
      Object.keys(s).forEach(function (k) {
        if (k.indexOf('attr.') === 0) { attrs[k.slice(5)] = [].concat(s[k]); }
      });
      return {
        q: s.q || '', category: s.category || null, brand: s.brand ? [].concat(s.brand) : [], attrs: attrs,
        min: s.min || null, max: s.max || null, inStock: s.inStock === 'true', onSale: s.onSale === 'true',
        sort: s.sort || 'RELEVANCE', page: parseInt(s.page || '0', 10)
      };
    }

    function writeState() {
      var st = vm.state;
      var params = { q: st.q || null, category: st.category, brand: st.brand.length ? st.brand : null, min: st.min, max: st.max,
        inStock: st.inStock ? 'true' : null, onSale: st.onSale ? 'true' : null,
        sort: st.sort !== 'RELEVANCE' ? st.sort : null, page: st.page ? String(st.page) : null };
      Object.keys(st.attrs).forEach(function (k) { if (st.attrs[k].length) { params['attr.' + k] = st.attrs[k]; } });
      $location.search(params);
      return params;
    }

    vm.state = readState();
    vm.q = vm.state.q;

    vm.search = function (keepPage) {
      if (!keepPage) { vm.state.page = 0; }
      var params = writeState();
      $http.get('/api/search', { params: params }).then(function (res) { vm.result = res.data; });
    };

    vm.submit = function () {
      vm.state.q = vm.q;
      vm.suggestions = [];
      vm.search();
    };

    vm.suggest = function () {
      if (!vm.q || vm.q.length < 2) { vm.suggestions = []; return; }
      $http.get('/api/suggest', { params: { q: vm.q } }).then(function (res) { vm.suggestions = res.data; });
    };

    vm.toggle = function (facet, bucket) {
      var st = vm.state;
      if (facet.name === 'brand') {
        var i = st.brand.indexOf(bucket.value);
        if (i >= 0) { st.brand.splice(i, 1); } else { st.brand.push(bucket.value); }
      } else if (facet.name === 'price') {
        var parts = bucket.value.split('-');
        var same = st.min === parts[0] && st.max === (parts[1] || null);
        st.min = same ? null : parts[0];
        st.max = same ? null : (parts[1] || null);
      } else if (facet.name.indexOf('attr:') === 0) {
        var key = facet.name.slice(5);
        var list = st.attrs[key] = st.attrs[key] || [];
        var j = list.indexOf(bucket.value);
        if (j >= 0) { list.splice(j, 1); } else { list.push(bucket.value); }
      }
      vm.search();
    };

    vm.setCategory = function (path) {
      vm.state.category = path;
      vm.search();
    };

    vm.crumbs = function () {
      var parts = (vm.state.category || '').split('/');
      return parts.map(function (label, i) { return { label: label, path: parts.slice(0, i + 1).join('/') }; });
    };

    vm.pages = function () { return vm.result ? Math.ceil(vm.result.total / vm.result.size) : 0; };
    vm.go = function (page) { vm.state.page = page; vm.search(true); };

    vm.search(true);
  }]);
