document.addEventListener('DOMContentLoaded', function () {
  if (window.flatpickr) {
    flatpickr('.flatpickr-datetime', {
      enableTime: true,
      time_24hr: true,
      dateFormat: 'Y-m-d\\TH:i',
      altInput: true,
      altFormat: 'j M Y, H:i'
    });
  }
});
