document.addEventListener('submit', function (e) {
  var btn = e.target.querySelector('[type=submit]');
  if (btn && !btn.disabled) {
    btn.disabled = true;
    btn.innerHTML = '<span class="loading loading-spinner loading-xs"></span>';
  }
});
