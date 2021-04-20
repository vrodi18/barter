var socker = new WebSocket('ws://localhost:5000/ws/graph/')
socker.onmessage = function(e) {
    var djangoData = JSON.parse(e.data);
    console.log(djangoData);
    document.querySelector('#app').innerText = djangoData.value;
}