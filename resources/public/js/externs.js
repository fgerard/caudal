var AreaChart = this.AreaChart = function (e) {
  return new google.visualization.AreaChart(e);
};
var PieChart = this.PieChart = function(e) {
  return new google.visualization.PieChart(e);
};
var BarChart = this.BarChart = function(e) {
  return new google.visualization.BarChart(e);
};
var DataTable = this.DataTable = function () {
  return new google.visualization.DataTable();
};
var Arr2DataTable = this.Arr2DataTable = function(arr) {
  return google.visualization.arrayToDataTable(arr);
};
var getNumberOfColumns = this.getNumberOfColumns = function(dt) {
  return dt.getNumberOfColumns();
};
var getNumberOfRows = this.getNumberOfRows = function(dt) {
  return dt.getNumberOfRows();
};
var addColumn = this.addColumn = function(dt, type, name) {
  dt.addColumn(type, name);
};
var removeColumn = this.removeColumn = function(dt, type, name) {
  dt.removeColumn(type, name);
};
var removeRows = this.removeRows = function(dt, init, end) {
  dt.removeRows(init,end);
};
var addRows = this.addRows = function(dt, v) {
  dt.addRows(v);
};
var draw = this.draw = function(chart, dt, opts) {
  chart.draw(dt,opts);
};
