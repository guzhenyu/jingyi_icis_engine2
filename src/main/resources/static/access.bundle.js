/*
 * ATTENTION: The "eval" devtool has been used (maybe by default in mode: "development").
 * This devtool is neither made for production nor for readable output files.
 * It uses "eval()" calls to create a separate source file in the browser devtools.
 * If you are trying to read the output file, select a different devtool (https://webpack.js.org/configuration/devtool/)
 * or disable the default devtool with "devtool: false".
 * If you are looking for production-ready output files, see mode: "production" (https://webpack.js.org/configuration/mode/).
 */
/******/ (() => { // webpackBootstrap
/******/ 	"use strict";
/******/ 	var __webpack_modules__ = ({

/***/ "./src/api/Settings.ts"
/*!*****************************!*\
  !*** ./src/api/Settings.ts ***!
  \*****************************/
(__unused_webpack_module, __webpack_exports__, __webpack_require__) {

eval("{/* harmony export */ __webpack_require__.d(__webpack_exports__, {\n/* harmony export */   DEFAULT_CHOOSE_FIRST_DEPARTMENT: () => (/* binding */ DEFAULT_CHOOSE_FIRST_DEPARTMENT)\n/* harmony export */ });\n/* unused harmony exports USERINFO_REFRESH_SECONDS, INLINE_PATIENTS_REFRESH_SECONDS, PATIENT_DETAILS_REFRESH_SECONDS, DEPT_TUBE_TYPES_REFRESH_SECONDS, GET_CONFIG_REFRESH_SECONDS, ALLDEPTNAME_REFRESH_SECONDS, GET_SCORE_GROUP_REFRESH_SECONDS, SUPER_WIDE_MODAL_WIDTH, WIDE_MODAL_WIDTH, MEDIUM_MODAL_WIDTH, SMALL_MODAL_WIDTH, MODAL_WIDTH_720, MODAL_WIDTH_520, THROTTLE_LEVEL_1, DEBOUNCE_LEVEL_1, DEBOUNCE_LEVEL_2, NURSING_RECORD_HISTORY_MAX_LEN, CUSTOM_FORM_EDITOR_DEFAULT_BORDER_WIDTH, CUSTOM_FORM_EDITOR_DEFAULT_BORDER_COLOR, DICTIONARY_CONFIG_FREQ_SUPPORT_NURSING_ORDER, QUALITY_CONTROL_GET_ALL_DATA, DEFAULT_HOSPITAL_LOGO_SRC, HospitalLogoSrc, normalizeHospitalLogoB64, getHospitalLogoSrc, useHospitalLogoSrc */\n/* harmony import */ var react__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! react */ \"./node_modules/react/index.js\");\n/* harmony import */ var react__WEBPACK_IMPORTED_MODULE_0___default = /*#__PURE__*/__webpack_require__.n(react__WEBPACK_IMPORTED_MODULE_0__);\n/* harmony import */ var react_redux__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! react-redux */ \"./node_modules/react-redux/dist/react-redux.mjs\");\n/* harmony import */ var _assets_logo_XinAnCountyPeopleHospital_png__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../assets/logo/XinAnCountyPeopleHospital.png */ \"./src/assets/logo/XinAnCountyPeopleHospital.png\");\n/* harmony import */ var _store_slices_AppSettingApi__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../store/slices/AppSettingApi */ \"./src/store/slices/AppSettingApi.ts\");\n\n\n\n\nvar USERINFO_REFRESH_SECONDS = 180;\nvar INLINE_PATIENTS_REFRESH_SECONDS = 30;\nvar PATIENT_DETAILS_REFRESH_SECONDS = 30;\nvar DEPT_TUBE_TYPES_REFRESH_SECONDS = 300;\nvar GET_CONFIG_REFRESH_SECONDS = 300;\nvar ALLDEPTNAME_REFRESH_SECONDS = 300;\nvar GET_SCORE_GROUP_REFRESH_SECONDS = 300;\nvar DEFAULT_CHOOSE_FIRST_DEPARTMENT = false; // 本地调试环境，默认选择第一个部门\n\n// 页面展示相关配置\nvar SUPER_WIDE_MODAL_WIDTH = \"95%\";\nvar WIDE_MODAL_WIDTH = \"72%\";\nvar MEDIUM_MODAL_WIDTH = \"50%\";\nvar SMALL_MODAL_WIDTH = \"30%\";\nvar MODAL_WIDTH_720 = 720;\nvar MODAL_WIDTH_520 = 520;\n\n// 防抖、节流等方法的时间配置\nvar THROTTLE_LEVEL_1 = 500;\nvar DEBOUNCE_LEVEL_1 = 500;\nvar DEBOUNCE_LEVEL_2 = 1000;\n\n// 护理记录输入框前进后退保存的历史记录最大长度值\nvar NURSING_RECORD_HISTORY_MAX_LEN = -1;\n\n// 自定义表单编辑器默认组件配置\nvar CUSTOM_FORM_EDITOR_DEFAULT_BORDER_WIDTH = 2;\nvar CUSTOM_FORM_EDITOR_DEFAULT_BORDER_COLOR = '#000000';\n\n// 字典配置频次配置支持频率类型\nvar DICTIONARY_CONFIG_FREQ_SUPPORT_NURSING_ORDER = false;\n\n// 质控统计获取数据方式\n// false:监听code和时间范围变化多次请求数据(每次请求一项数据)\n// true:监听时间范围变化一次请求所有code数据,多请求并发(每次请求全部项数据)\nvar QUALITY_CONTROL_GET_ALL_DATA = true;\nvar DEFAULT_HOSPITAL_LOGO_SRC = _assets_logo_XinAnCountyPeopleHospital_png__WEBPACK_IMPORTED_MODULE_2__;\nvar HospitalLogoSrc = DEFAULT_HOSPITAL_LOGO_SRC;\nvar detectHospitalLogoMimeType = function detectHospitalLogoMimeType(customLogoB64) {\n  if (customLogoB64.startsWith('iVBOR')) return 'image/png';\n  if (customLogoB64.startsWith('/9j/')) return 'image/jpeg';\n  if (customLogoB64.startsWith('R0lGOD')) return 'image/gif';\n  if (customLogoB64.startsWith('UklGR')) return 'image/webp';\n  return 'image/png';\n};\nvar normalizeHospitalLogoB64 = function normalizeHospitalLogoB64(customLogoB64) {\n  var normalized = (customLogoB64 === null || customLogoB64 === void 0 ? void 0 : customLogoB64.trim()) || '';\n  if (!normalized) return '';\n  var commaIndex = normalized.indexOf(',');\n  if (normalized.startsWith('data:image/') && commaIndex >= 0) {\n    return normalized.slice(commaIndex + 1);\n  }\n  return normalized;\n};\nvar getHospitalLogoSrc = function getHospitalLogoSrc(customLogoB64) {\n  var normalized = normalizeHospitalLogoB64(customLogoB64);\n  if (!normalized) return DEFAULT_HOSPITAL_LOGO_SRC;\n  return \"data:\".concat(detectHospitalLogoMimeType(normalized), \";base64,\").concat(normalized);\n};\nvar useHospitalLogoSrc = function useHospitalLogoSrc() {\n  var dispatch = (0,react_redux__WEBPACK_IMPORTED_MODULE_1__.useDispatch)();\n  var customLogoB64 = (0,react_redux__WEBPACK_IMPORTED_MODULE_1__.useSelector)(function (state) {\n    return state.appSetting.customLogoB64;\n  });\n  var logoLoaded = (0,react_redux__WEBPACK_IMPORTED_MODULE_1__.useSelector)(function (state) {\n    return state.appSetting.logoLoaded;\n  });\n  var logoLoading = (0,react_redux__WEBPACK_IMPORTED_MODULE_1__.useSelector)(function (state) {\n    return state.appSetting.logoLoading;\n  });\n  (0,react__WEBPACK_IMPORTED_MODULE_0__.useEffect)(function () {\n    if (!logoLoaded && !logoLoading) {\n      dispatch((0,_store_slices_AppSettingApi__WEBPACK_IMPORTED_MODULE_3__.getLogo)({}));\n    }\n  }, [dispatch, logoLoaded, logoLoading]);\n  return getHospitalLogoSrc(customLogoB64);\n};\n\n//# sourceURL=webpack://jingyi_icis_frontend/./src/api/Settings.ts?\n}");

/***/ },

/***/ "./src/pages/access/Access.tsx"
/*!*************************************!*\
  !*** ./src/pages/access/Access.tsx ***!
  \*************************************/
(__unused_webpack_module, __webpack_exports__, __webpack_require__) {

eval("{/* harmony export */ __webpack_require__.d(__webpack_exports__, {\n/* harmony export */   \"default\": () => (__WEBPACK_DEFAULT_EXPORT__)\n/* harmony export */ });\n/* harmony import */ var react__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! react */ \"./node_modules/react/index.js\");\n/* harmony import */ var react__WEBPACK_IMPORTED_MODULE_0___default = /*#__PURE__*/__webpack_require__.n(react__WEBPACK_IMPORTED_MODULE_0__);\n/* harmony import */ var react_redux__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! react-redux */ \"./node_modules/react-redux/dist/react-redux.mjs\");\n/* harmony import */ var antd__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! antd */ \"./node_modules/antd/es/empty/index.js\");\n/* harmony import */ var antd__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! antd */ \"./node_modules/antd/es/form/index.js\");\n/* harmony import */ var antd__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! antd */ \"./node_modules/antd/es/input/index.js\");\n/* harmony import */ var antd__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! antd */ \"./node_modules/antd/es/select/index.js\");\n/* harmony import */ var antd__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! antd */ \"./node_modules/antd/es/space/index.js\");\n/* harmony import */ var dayjs__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! dayjs */ \"./node_modules/dayjs/dayjs.min.js\");\n/* harmony import */ var dayjs__WEBPACK_IMPORTED_MODULE_7___default = /*#__PURE__*/__webpack_require__.n(dayjs__WEBPACK_IMPORTED_MODULE_7__);\n/* harmony import */ var _home_components_ToolbarWrap__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ../home/components/ToolbarWrap */ \"./src/pages/home/components/ToolbarWrap/index.tsx\");\n/* harmony import */ var _home_components_SimplePDFViewer__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__(/*! ../home/components/SimplePDFViewer */ \"./src/pages/home/components/SimplePDFViewer/index.tsx\");\n/* harmony import */ var _home_components_CustomDatePicker__WEBPACK_IMPORTED_MODULE_10__ = __webpack_require__(/*! ../home/components/CustomDatePicker */ \"./src/pages/home/components/CustomDatePicker/index.tsx\");\n/* harmony import */ var _index_module_scss__WEBPACK_IMPORTED_MODULE_11__ = __webpack_require__(/*! ./index.module.scss */ \"./src/pages/access/index.module.scss\");\nfunction _regenerator() { /*! regenerator-runtime -- Copyright (c) 2014-present, Facebook, Inc. -- license (MIT): https://github.com/babel/babel/blob/main/packages/babel-helpers/LICENSE */ var e, t, r = \"function\" == typeof Symbol ? Symbol : {}, n = r.iterator || \"@@iterator\", o = r.toStringTag || \"@@toStringTag\"; function i(r, n, o, i) { var c = n && n.prototype instanceof Generator ? n : Generator, u = Object.create(c.prototype); return _regeneratorDefine2(u, \"_invoke\", function (r, n, o) { var i, c, u, f = 0, p = o || [], y = !1, G = { p: 0, n: 0, v: e, a: d, f: d.bind(e, 4), d: function d(t, r) { return i = t, c = 0, u = e, G.n = r, a; } }; function d(r, n) { for (c = r, u = n, t = 0; !y && f && !o && t < p.length; t++) { var o, i = p[t], d = G.p, l = i[2]; r > 3 ? (o = l === n) && (u = i[(c = i[4]) ? 5 : (c = 3, 3)], i[4] = i[5] = e) : i[0] <= d && ((o = r < 2 && d < i[1]) ? (c = 0, G.v = n, G.n = i[1]) : d < l && (o = r < 3 || i[0] > n || n > l) && (i[4] = r, i[5] = n, G.n = l, c = 0)); } if (o || r > 1) return a; throw y = !0, n; } return function (o, p, l) { if (f > 1) throw TypeError(\"Generator is already running\"); for (y && 1 === p && d(p, l), c = p, u = l; (t = c < 2 ? e : u) || !y;) { i || (c ? c < 3 ? (c > 1 && (G.n = -1), d(c, u)) : G.n = u : G.v = u); try { if (f = 2, i) { if (c || (o = \"next\"), t = i[o]) { if (!(t = t.call(i, u))) throw TypeError(\"iterator result is not an object\"); if (!t.done) return t; u = t.value, c < 2 && (c = 0); } else 1 === c && (t = i[\"return\"]) && t.call(i), c < 2 && (u = TypeError(\"The iterator does not provide a '\" + o + \"' method\"), c = 1); i = e; } else if ((t = (y = G.n < 0) ? u : r.call(n, G)) !== a) break; } catch (t) { i = e, c = 1, u = t; } finally { f = 1; } } return { value: t, done: y }; }; }(r, o, i), !0), u; } var a = {}; function Generator() {} function GeneratorFunction() {} function GeneratorFunctionPrototype() {} t = Object.getPrototypeOf; var c = [][n] ? t(t([][n]())) : (_regeneratorDefine2(t = {}, n, function () { return this; }), t), u = GeneratorFunctionPrototype.prototype = Generator.prototype = Object.create(c); function f(e) { return Object.setPrototypeOf ? Object.setPrototypeOf(e, GeneratorFunctionPrototype) : (e.__proto__ = GeneratorFunctionPrototype, _regeneratorDefine2(e, o, \"GeneratorFunction\")), e.prototype = Object.create(u), e; } return GeneratorFunction.prototype = GeneratorFunctionPrototype, _regeneratorDefine2(u, \"constructor\", GeneratorFunctionPrototype), _regeneratorDefine2(GeneratorFunctionPrototype, \"constructor\", GeneratorFunction), GeneratorFunction.displayName = \"GeneratorFunction\", _regeneratorDefine2(GeneratorFunctionPrototype, o, \"GeneratorFunction\"), _regeneratorDefine2(u), _regeneratorDefine2(u, o, \"Generator\"), _regeneratorDefine2(u, n, function () { return this; }), _regeneratorDefine2(u, \"toString\", function () { return \"[object Generator]\"; }), (_regenerator = function _regenerator() { return { w: i, m: f }; })(); }\nfunction _regeneratorDefine2(e, r, n, t) { var i = Object.defineProperty; try { i({}, \"\", {}); } catch (e) { i = 0; } _regeneratorDefine2 = function _regeneratorDefine(e, r, n, t) { function o(r, n) { _regeneratorDefine2(e, r, function (e) { return this._invoke(r, n, e); }); } r ? i ? i(e, r, { value: n, enumerable: !t, configurable: !t, writable: !t }) : e[r] = n : (o(\"next\", 0), o(\"throw\", 1), o(\"return\", 2)); }, _regeneratorDefine2(e, r, n, t); }\nfunction asyncGeneratorStep(n, t, e, r, o, a, c) { try { var i = n[a](c), u = i.value; } catch (n) { return void e(n); } i.done ? t(u) : Promise.resolve(u).then(r, o); }\nfunction _asyncToGenerator(n) { return function () { var t = this, e = arguments; return new Promise(function (r, o) { var a = n.apply(t, e); function _next(n) { asyncGeneratorStep(a, r, o, _next, _throw, \"next\", n); } function _throw(n) { asyncGeneratorStep(a, r, o, _next, _throw, \"throw\", n); } _next(void 0); }); }; }\nfunction _slicedToArray(r, e) { return _arrayWithHoles(r) || _iterableToArrayLimit(r, e) || _unsupportedIterableToArray(r, e) || _nonIterableRest(); }\nfunction _nonIterableRest() { throw new TypeError(\"Invalid attempt to destructure non-iterable instance.\\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.\"); }\nfunction _unsupportedIterableToArray(r, a) { if (r) { if (\"string\" == typeof r) return _arrayLikeToArray(r, a); var t = {}.toString.call(r).slice(8, -1); return \"Object\" === t && r.constructor && (t = r.constructor.name), \"Map\" === t || \"Set\" === t ? Array.from(r) : \"Arguments\" === t || /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(t) ? _arrayLikeToArray(r, a) : void 0; } }\nfunction _arrayLikeToArray(r, a) { (null == a || a > r.length) && (a = r.length); for (var e = 0, n = Array(a); e < a; e++) n[e] = r[e]; return n; }\nfunction _iterableToArrayLimit(r, l) { var t = null == r ? null : \"undefined\" != typeof Symbol && r[Symbol.iterator] || r[\"@@iterator\"]; if (null != t) { var e, n, i, u, a = [], f = !0, o = !1; try { if (i = (t = t.call(r)).next, 0 === l) { if (Object(t) !== t) return; f = !1; } else for (; !(f = (e = i.call(t)).done) && (a.push(e.value), a.length !== l); f = !0); } catch (r) { o = !0, n = r; } finally { try { if (!f && null != t[\"return\"] && (u = t[\"return\"](), Object(u) !== u)) return; } finally { if (o) throw n; } } return a; } }\nfunction _arrayWithHoles(r) { if (Array.isArray(r)) return r; }\n\n\n\n\n\n\n\n\nvar Access = function Access() {\n  var dispatch = (0,react_redux__WEBPACK_IMPORTED_MODULE_1__.useDispatch)();\n  // const loading = useSelector((state: RootState) => state.login.loading)\n\n  var _useState = (0,react__WEBPACK_IMPORTED_MODULE_0__.useState)([]),\n    _useState2 = _slicedToArray(_useState, 2),\n    deptList = _useState2[0],\n    setDeptList = _useState2[1];\n  var _useState3 = (0,react__WEBPACK_IMPORTED_MODULE_0__.useState)([]),\n    _useState4 = _slicedToArray(_useState3, 2),\n    formList = _useState4[0],\n    setFormList = _useState4[1];\n  var _useState5 = (0,react__WEBPACK_IMPORTED_MODULE_0__.useState)(''),\n    _useState6 = _slicedToArray(_useState5, 2),\n    curDeptId = _useState6[0],\n    setCurDeptId = _useState6[1];\n  var _useState7 = (0,react__WEBPACK_IMPORTED_MODULE_0__.useState)('mrn'),\n    _useState8 = _slicedToArray(_useState7, 2),\n    searchType = _useState8[0],\n    setSearchType = _useState8[1];\n  var _useState9 = (0,react__WEBPACK_IMPORTED_MODULE_0__.useState)(''),\n    _useState0 = _slicedToArray(_useState9, 2),\n    searchValue = _useState0[0],\n    setSearchValue = _useState0[1];\n  var _useState1 = (0,react__WEBPACK_IMPORTED_MODULE_0__.useState)(''),\n    _useState10 = _slicedToArray(_useState1, 2),\n    selectedForm = _useState10[0],\n    setSelectedForm = _useState10[1];\n  var _useState11 = (0,react__WEBPACK_IMPORTED_MODULE_0__.useState)(dayjs__WEBPACK_IMPORTED_MODULE_7___default()()),\n    _useState12 = _slicedToArray(_useState11, 2),\n    searchTime = _useState12[0],\n    setSearchTime = _useState12[1];\n  var _useState13 = (0,react__WEBPACK_IMPORTED_MODULE_0__.useState)('202601/34_肖祥权_20260106_monitoring_report.pdf'),\n    _useState14 = _slicedToArray(_useState13, 2),\n    pdfUrl = _useState14[0],\n    setPdfUrl = _useState14[1];\n  (0,react__WEBPACK_IMPORTED_MODULE_0__.useEffect)(function () {\n    // http://localhost:8000/access.html?dept_code=99999&mrn=123321&form_code=nursing_form\n    // 从url中获取参数\n    var params = new URLSearchParams(location.search);\n    var paramsMap = Object.fromEntries(params);\n    if (paramsMap.dept_code) {\n      setCurDeptId(paramsMap.dept_code);\n    }\n    if (paramsMap.mrn) {\n      setSearchType('mrn');\n      setSearchValue(paramsMap.mrn);\n    } else if (paramsMap.his_pid) {\n      setSearchType('hisPid');\n      setSearchValue(paramsMap.his_pid);\n    }\n    if (paramsMap.form_code) {\n      setSelectedForm(paramsMap.form_code);\n    }\n    if (paramsMap.time) {\n      setSearchTime(dayjs__WEBPACK_IMPORTED_MODULE_7___default()(paramsMap.time));\n    }\n  }, []);\n\n  // 以下接口需求,无需登录即可访问\n\n  // 获取所有部门,无需登录即可访问\n  var fetchNoAuthGetDeptList = /*#__PURE__*/function () {\n    var _ref = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee() {\n      return _regenerator().w(function (_context) {\n        while (1) switch (_context.n) {\n          case 0:\n            return _context.a(2);\n        }\n      }, _callee);\n    }));\n    return function fetchNoAuthGetDeptList() {\n      return _ref.apply(this, arguments);\n    };\n  }();\n  // 获取选择部门配置的可访问表单,无需登录即可访问\n  var fetchNoAuthGetFormListConfig = /*#__PURE__*/function () {\n    var _ref2 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee2() {\n      return _regenerator().w(function (_context2) {\n        while (1) switch (_context2.n) {\n          case 0:\n            return _context2.a(2);\n        }\n      }, _callee2);\n    }));\n    return function fetchNoAuthGetFormListConfig() {\n      return _ref2.apply(this, arguments);\n    };\n  }();\n\n  // Toolbar 渲染\n  var toolbarLeftRender = function toolbarLeftRender() {\n    return /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(\"div\", {\n      className: _index_module_scss__WEBPACK_IMPORTED_MODULE_11__[\"default\"].toolbarLeft\n    }, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_3__[\"default\"].Item, {\n      label: \"\\u79D1\\u5BA4\",\n      name: \"deptId\",\n      colon: false\n    }, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_5__[\"default\"], {\n      options: deptList,\n      style: {\n        width: 120\n      }\n    })), /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_3__[\"default\"].Item, {\n      name: \"searchType\",\n      colon: false\n    }, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_6__[\"default\"].Compact, null, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_5__[\"default\"], {\n      options: [{\n        label: '住院号',\n        value: 'mrn'\n      }, {\n        label: '病人ID',\n        value: 'hisPid'\n      }],\n      value: searchType,\n      onChange: function onChange(value) {\n        return setSearchType(value);\n      },\n      style: {\n        width: 100\n      }\n    }), /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_4__[\"default\"], {\n      style: {\n        width: 120\n      },\n      placeholder: \"\\u8BF7\\u8F93\\u5165\\u641C\\u7D22\\u5185\\u5BB9\",\n      value: searchValue,\n      onChange: function onChange(e) {\n        return setSearchValue(e.target.value);\n      }\n    }))), /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_3__[\"default\"].Item, {\n      label: \"\\u8868\\u5355\",\n      name: \"formId\",\n      colon: false\n    }, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_5__[\"default\"], {\n      style: {\n        width: 120\n      },\n      options: formList,\n      value: selectedForm,\n      onChange: function onChange(value) {\n        return setSelectedForm(value);\n      }\n    })), /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_3__[\"default\"].Item, {\n      label: \"\\u65F6\\u95F4\",\n      name: \"searchTime\",\n      colon: false\n    }, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(_home_components_CustomDatePicker__WEBPACK_IMPORTED_MODULE_10__[\"default\"], {\n      style: {\n        width: 120\n      },\n      format: \"YYYY-MM-DD HH:mm\",\n      showTime: true,\n      defaultValue: searchTime,\n      onChange: function onChange(value) {\n        return setSearchTime(value);\n      }\n    })));\n  };\n  return /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(\"div\", {\n    className: _index_module_scss__WEBPACK_IMPORTED_MODULE_11__[\"default\"].accessContainer\n  }, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(\"div\", {\n    className: _index_module_scss__WEBPACK_IMPORTED_MODULE_11__[\"default\"].accessContent\n  }, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(_home_components_ToolbarWrap__WEBPACK_IMPORTED_MODULE_8__[\"default\"], {\n    left: toolbarLeftRender(),\n    right: /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(\"div\", {\n      style: {\n        display: 'flex',\n        gap: 24\n      }\n    }, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(\"div\", null, \"\\u5165\\u79D1\\u65F6\\u95F4: 2025-01-01 00:00:00\"), /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(\"div\", null, \"\\u51FA\\u79D1\\u65F6\\u95F4: 2025-01-01 00:00:00\"))\n  }), /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(\"div\", {\n    className: _index_module_scss__WEBPACK_IMPORTED_MODULE_11__[\"default\"].content\n  }, pdfUrl ? /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(_home_components_SimplePDFViewer__WEBPACK_IMPORTED_MODULE_9__[\"default\"], {\n    pdfUrl: pdfUrl\n  }) : /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_2__[\"default\"], null))));\n};\n/* harmony default export */ const __WEBPACK_DEFAULT_EXPORT__ = (Access);\n\n//# sourceURL=webpack://jingyi_icis_frontend/./src/pages/access/Access.tsx?\n}");

/***/ },

/***/ "./src/pages/access/index.module.scss"
/*!********************************************!*\
  !*** ./src/pages/access/index.module.scss ***!
  \********************************************/
(__unused_webpack_module, __webpack_exports__, __webpack_require__) {

eval("{/* harmony export */ __webpack_require__.d(__webpack_exports__, {\n/* harmony export */   \"default\": () => (__WEBPACK_DEFAULT_EXPORT__)\n/* harmony export */ });\n// extracted by mini-css-extract-plugin\n/* harmony default export */ const __WEBPACK_DEFAULT_EXPORT__ = ({\"accessContainer\":\"index-module__accessContainer--bAGS1\",\"accessContent\":\"index-module__accessContent--W0YtG\",\"toolbarLeft\":\"index-module__toolbarLeft--KSBCg\",\"content\":\"index-module__content--eV1QR\"});\n\n//# sourceURL=webpack://jingyi_icis_frontend/./src/pages/access/index.module.scss?\n}");

/***/ },

/***/ "./src/pages/access/index.tsx"
/*!************************************!*\
  !*** ./src/pages/access/index.tsx ***!
  \************************************/
(__unused_webpack_module, __unused_webpack___webpack_exports__, __webpack_require__) {

eval("{/* harmony import */ var react__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! react */ \"./node_modules/react/index.js\");\n/* harmony import */ var react__WEBPACK_IMPORTED_MODULE_0___default = /*#__PURE__*/__webpack_require__.n(react__WEBPACK_IMPORTED_MODULE_0__);\n/* harmony import */ var react_dom_client__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! react-dom/client */ \"./node_modules/react-dom/client.js\");\n/* harmony import */ var react_redux__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! react-redux */ \"./node_modules/react-redux/dist/react-redux.mjs\");\n/* harmony import */ var antd__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! antd */ \"./node_modules/antd/es/config-provider/index.js\");\n/* harmony import */ var antd_locale_zh_CN__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! antd/locale/zh_CN */ \"./node_modules/antd/lib/locale/zh_CN.js\");\n/* harmony import */ var dayjs_locale_zh_cn__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! dayjs/locale/zh-cn */ \"./node_modules/dayjs/locale/zh-cn.js\");\n/* harmony import */ var dayjs_locale_zh_cn__WEBPACK_IMPORTED_MODULE_5___default = /*#__PURE__*/__webpack_require__.n(dayjs_locale_zh_cn__WEBPACK_IMPORTED_MODULE_5__);\n/* harmony import */ var _store_store__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../../store/store */ \"./src/store/store.ts\");\n/* harmony import */ var _utils__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ../../utils */ \"./src/utils/index.ts\");\n/* harmony import */ var _Access__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ./Access */ \"./src/pages/access/Access.tsx\");\n/* harmony import */ var _styles_global_scss__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__(/*! ../../styles/global.scss */ \"./src/styles/global.scss\");\n\n\n\n\n\n\n\n\n\n\n\n// 全局禁用右键菜单\n(0,_utils__WEBPACK_IMPORTED_MODULE_7__.disableRightClick)();\nvar root = (0,react_dom_client__WEBPACK_IMPORTED_MODULE_1__.createRoot)(document.getElementById('root'));\nroot.render(/*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement((react__WEBPACK_IMPORTED_MODULE_0___default().StrictMode), null, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(react_redux__WEBPACK_IMPORTED_MODULE_2__.Provider, {\n  store: _store_store__WEBPACK_IMPORTED_MODULE_6__[\"default\"]\n}, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(antd__WEBPACK_IMPORTED_MODULE_3__[\"default\"], {\n  locale: antd_locale_zh_CN__WEBPACK_IMPORTED_MODULE_4__[\"default\"]\n}, /*#__PURE__*/react__WEBPACK_IMPORTED_MODULE_0___default().createElement(_Access__WEBPACK_IMPORTED_MODULE_8__[\"default\"], null)))));\n// root.render(<Provider store={store}><App /></Provider>); // 生产环境中去掉<React.StrictMode>\n\n//# sourceURL=webpack://jingyi_icis_frontend/./src/pages/access/index.tsx?\n}");

/***/ }

/******/ 	});
/************************************************************************/
/******/ 	// The module cache
/******/ 	var __webpack_module_cache__ = {};
/******/ 	
/******/ 	// The require function
/******/ 	function __webpack_require__(moduleId) {
/******/ 		// Check if module is in cache
/******/ 		var cachedModule = __webpack_module_cache__[moduleId];
/******/ 		if (cachedModule !== undefined) {
/******/ 			return cachedModule.exports;
/******/ 		}
/******/ 		// Check if module exists (development only)
/******/ 		if (__webpack_modules__[moduleId] === undefined) {
/******/ 			var e = new Error("Cannot find module '" + moduleId + "'");
/******/ 			e.code = 'MODULE_NOT_FOUND';
/******/ 			throw e;
/******/ 		}
/******/ 		// Create a new module (and put it into the cache)
/******/ 		var module = __webpack_module_cache__[moduleId] = {
/******/ 			id: moduleId,
/******/ 			loaded: false,
/******/ 			exports: {}
/******/ 		};
/******/ 	
/******/ 		// Execute the module function
/******/ 		__webpack_modules__[moduleId].call(module.exports, module, module.exports, __webpack_require__);
/******/ 	
/******/ 		// Flag the module as loaded
/******/ 		module.loaded = true;
/******/ 	
/******/ 		// Return the exports of the module
/******/ 		return module.exports;
/******/ 	}
/******/ 	
/******/ 	// expose the modules object (__webpack_modules__)
/******/ 	__webpack_require__.m = __webpack_modules__;
/******/ 	
/************************************************************************/
/******/ 	/* webpack/runtime/chunk loaded */
/******/ 	(() => {
/******/ 		var deferred = [];
/******/ 		__webpack_require__.O = (result, chunkIds, fn, priority) => {
/******/ 			if(chunkIds) {
/******/ 				priority = priority || 0;
/******/ 				for(var i = deferred.length; i > 0 && deferred[i - 1][2] > priority; i--) deferred[i] = deferred[i - 1];
/******/ 				deferred[i] = [chunkIds, fn, priority];
/******/ 				return;
/******/ 			}
/******/ 			var notFulfilled = Infinity;
/******/ 			for (var i = 0; i < deferred.length; i++) {
/******/ 				var [chunkIds, fn, priority] = deferred[i];
/******/ 				var fulfilled = true;
/******/ 				for (var j = 0; j < chunkIds.length; j++) {
/******/ 					if ((priority & 1 === 0 || notFulfilled >= priority) && Object.keys(__webpack_require__.O).every((key) => (__webpack_require__.O[key](chunkIds[j])))) {
/******/ 						chunkIds.splice(j--, 1);
/******/ 					} else {
/******/ 						fulfilled = false;
/******/ 						if(priority < notFulfilled) notFulfilled = priority;
/******/ 					}
/******/ 				}
/******/ 				if(fulfilled) {
/******/ 					deferred.splice(i--, 1)
/******/ 					var r = fn();
/******/ 					if (r !== undefined) result = r;
/******/ 				}
/******/ 			}
/******/ 			return result;
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/compat get default export */
/******/ 	(() => {
/******/ 		// getDefaultExport function for compatibility with non-harmony modules
/******/ 		__webpack_require__.n = (module) => {
/******/ 			var getter = module && module.__esModule ?
/******/ 				() => (module['default']) :
/******/ 				() => (module);
/******/ 			__webpack_require__.d(getter, { a: getter });
/******/ 			return getter;
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/create fake namespace object */
/******/ 	(() => {
/******/ 		var getProto = Object.getPrototypeOf ? (obj) => (Object.getPrototypeOf(obj)) : (obj) => (obj.__proto__);
/******/ 		var leafPrototypes;
/******/ 		// create a fake namespace object
/******/ 		// mode & 1: value is a module id, require it
/******/ 		// mode & 2: merge all properties of value into the ns
/******/ 		// mode & 4: return value when already ns object
/******/ 		// mode & 16: return value when it's Promise-like
/******/ 		// mode & 8|1: behave like require
/******/ 		__webpack_require__.t = function(value, mode) {
/******/ 			if(mode & 1) value = this(value);
/******/ 			if(mode & 8) return value;
/******/ 			if(typeof value === 'object' && value) {
/******/ 				if((mode & 4) && value.__esModule) return value;
/******/ 				if((mode & 16) && typeof value.then === 'function') return value;
/******/ 			}
/******/ 			var ns = Object.create(null);
/******/ 			__webpack_require__.r(ns);
/******/ 			var def = {};
/******/ 			leafPrototypes = leafPrototypes || [null, getProto({}), getProto([]), getProto(getProto)];
/******/ 			for(var current = mode & 2 && value; (typeof current == 'object' || typeof current == 'function') && !~leafPrototypes.indexOf(current); current = getProto(current)) {
/******/ 				Object.getOwnPropertyNames(current).forEach((key) => (def[key] = () => (value[key])));
/******/ 			}
/******/ 			def['default'] = () => (value);
/******/ 			__webpack_require__.d(ns, def);
/******/ 			return ns;
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/define property getters */
/******/ 	(() => {
/******/ 		// define getter functions for harmony exports
/******/ 		__webpack_require__.d = (exports, definition) => {
/******/ 			for(var key in definition) {
/******/ 				if(__webpack_require__.o(definition, key) && !__webpack_require__.o(exports, key)) {
/******/ 					Object.defineProperty(exports, key, { enumerable: true, get: definition[key] });
/******/ 				}
/******/ 			}
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/global */
/******/ 	(() => {
/******/ 		__webpack_require__.g = (function() {
/******/ 			if (typeof globalThis === 'object') return globalThis;
/******/ 			try {
/******/ 				return this || new Function('return this')();
/******/ 			} catch (e) {
/******/ 				if (typeof window === 'object') return window;
/******/ 			}
/******/ 		})();
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/harmony module decorator */
/******/ 	(() => {
/******/ 		__webpack_require__.hmd = (module) => {
/******/ 			module = Object.create(module);
/******/ 			if (!module.children) module.children = [];
/******/ 			Object.defineProperty(module, 'exports', {
/******/ 				enumerable: true,
/******/ 				set: () => {
/******/ 					throw new Error('ES Modules may not assign module.exports or exports.*, Use ESM export syntax, instead: ' + module.id);
/******/ 				}
/******/ 			});
/******/ 			return module;
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/hasOwnProperty shorthand */
/******/ 	(() => {
/******/ 		__webpack_require__.o = (obj, prop) => (Object.prototype.hasOwnProperty.call(obj, prop))
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/make namespace object */
/******/ 	(() => {
/******/ 		// define __esModule on exports
/******/ 		__webpack_require__.r = (exports) => {
/******/ 			if(typeof Symbol !== 'undefined' && Symbol.toStringTag) {
/******/ 				Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });
/******/ 			}
/******/ 			Object.defineProperty(exports, '__esModule', { value: true });
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/node module decorator */
/******/ 	(() => {
/******/ 		__webpack_require__.nmd = (module) => {
/******/ 			module.paths = [];
/******/ 			if (!module.children) module.children = [];
/******/ 			return module;
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/runtimeId */
/******/ 	(() => {
/******/ 		__webpack_require__.j = "access";
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/publicPath */
/******/ 	(() => {
/******/ 		__webpack_require__.p = "/";
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/jsonp chunk loading */
/******/ 	(() => {
/******/ 		// no baseURI
/******/ 		
/******/ 		// object to store loaded and loading chunks
/******/ 		// undefined = chunk not loaded, null = chunk preloaded/prefetched
/******/ 		// [resolve, reject, Promise] = chunk loading, 0 = chunk loaded
/******/ 		var installedChunks = {
/******/ 			"access": 0
/******/ 		};
/******/ 		
/******/ 		// no chunk on demand loading
/******/ 		
/******/ 		// no prefetching
/******/ 		
/******/ 		// no preloaded
/******/ 		
/******/ 		// no HMR
/******/ 		
/******/ 		// no HMR manifest
/******/ 		
/******/ 		__webpack_require__.O.j = (chunkId) => (installedChunks[chunkId] === 0);
/******/ 		
/******/ 		// install a JSONP callback for chunk loading
/******/ 		var webpackJsonpCallback = (parentChunkLoadingFunction, data) => {
/******/ 			var [chunkIds, moreModules, runtime] = data;
/******/ 			// add "moreModules" to the modules object,
/******/ 			// then flag all "chunkIds" as loaded and fire callback
/******/ 			var moduleId, chunkId, i = 0;
/******/ 			if(chunkIds.some((id) => (installedChunks[id] !== 0))) {
/******/ 				for(moduleId in moreModules) {
/******/ 					if(__webpack_require__.o(moreModules, moduleId)) {
/******/ 						__webpack_require__.m[moduleId] = moreModules[moduleId];
/******/ 					}
/******/ 				}
/******/ 				if(runtime) var result = runtime(__webpack_require__);
/******/ 			}
/******/ 			if(parentChunkLoadingFunction) parentChunkLoadingFunction(data);
/******/ 			for(;i < chunkIds.length; i++) {
/******/ 				chunkId = chunkIds[i];
/******/ 				if(__webpack_require__.o(installedChunks, chunkId) && installedChunks[chunkId]) {
/******/ 					installedChunks[chunkId][0]();
/******/ 				}
/******/ 				installedChunks[chunkId] = 0;
/******/ 			}
/******/ 			return __webpack_require__.O(result);
/******/ 		}
/******/ 		
/******/ 		var chunkLoadingGlobal = self["webpackChunkjingyi_icis_frontend"] = self["webpackChunkjingyi_icis_frontend"] || [];
/******/ 		chunkLoadingGlobal.forEach(webpackJsonpCallback.bind(null, 0));
/******/ 		chunkLoadingGlobal.push = webpackJsonpCallback.bind(null, chunkLoadingGlobal.push.bind(chunkLoadingGlobal));
/******/ 	})();
/******/ 	
/************************************************************************/
/******/ 	
/******/ 	// startup
/******/ 	// Load entry module and return exports
/******/ 	// This entry module depends on other loaded chunks and execution need to be delayed
/******/ 	var __webpack_exports__ = __webpack_require__.O(undefined, ["react-vendor","vendors","common"], () => (__webpack_require__("./src/pages/access/index.tsx")))
/******/ 	__webpack_exports__ = __webpack_require__.O(__webpack_exports__);
/******/ 	
/******/ })()
;