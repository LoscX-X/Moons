(() => {
  const showcase = document.querySelector('.module-showcase');
  if (!showcase) return;
  const tablist = showcase.querySelector('.module-tabs');
  const tabs = [...tablist.querySelectorAll('.module-tab')];
  const panels = tabs.map(tab => document.getElementById(tab.hash.slice(1)));
  const mobile = window.matchMedia('(max-width: 700px)');
  if (panels.some(panel => !panel)) return;

  function activate(index, focus = false) {
    tabs.forEach((tab, current) => {
      const selected = current === index;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
      panels[current].hidden = !selected;
    });
    if (focus) tabs[index].focus({ preventScroll: true });
  }

  tablist.setAttribute('role', 'tablist');
  function setOrientation() {
    tablist.setAttribute('aria-orientation', mobile.matches ? 'horizontal' : 'vertical');
  }
  setOrientation();
  mobile.addEventListener('change', setOrientation);
  tabs.forEach((tab, index) => {
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-controls', panels[index].id);
    panels[index].setAttribute('role', 'tabpanel');
    panels[index].setAttribute('aria-labelledby', tab.id);
    panels[index].tabIndex = 0;
    tab.addEventListener('click', event => {
      event.preventDefault();
      activate(index);
    });
    tab.addEventListener('keydown', event => {
      let next;
      if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = tabs.length - 1;
      else if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % tabs.length;
      else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (index - 1 + tabs.length) % tabs.length;
      else return;
      event.preventDefault();
      activate(next, true);
    });
  });
  showcase.classList.add('is-enhanced');
  const initial = tabs.findIndex(tab => tab.hash === location.hash);
  activate(initial >= 0 ? initial : 0);
  window.addEventListener('hashchange', () => {
    const index = tabs.findIndex(tab => tab.hash === location.hash);
    if (index >= 0) activate(index);
  });
})();
