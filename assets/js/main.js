document.querySelectorAll(".copy-btn").forEach((btn) => {
  const target = document.querySelector(btn.dataset.copyTarget);
  const label = btn.querySelector(".copy-label");
  if (!target || !label) return;

  const defaultLabel = label.textContent;

  btn.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(target.textContent);
    } catch (err) {
      return;
    }
    btn.classList.add("copied");
    label.textContent = "Copied";
    setTimeout(() => {
      btn.classList.remove("copied");
      label.textContent = defaultLabel;
    }, 1800);
  });
});
