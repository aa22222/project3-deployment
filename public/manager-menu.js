const menuRows = [...document.querySelectorAll('.menu-edit-row')];
const menuSearch = document.getElementById('manager-menu-search');
function filterMenu() {
    const search = menuSearch.value.trim().toLowerCase();
    menuRows.forEach(row => {
        row.hidden = !row.elements.name.value.toLowerCase().includes(search);
    });
    if (menuRows.length) {
        document.getElementById('manager-menu-message').textContent = menuRows.some(row => !row.hidden) ? '' : 'No matching menu items.';
    }
}
menuSearch.addEventListener('input', filterMenu);
menuRows.forEach(row => {
    const feedback = row.querySelector('.menu-edit-feedback');
    row.addEventListener('input', () => { feedback.textContent = 'Unsaved changes'; });
    row.addEventListener('reset', () => { feedback.textContent = ''; });
    row.addEventListener('submit', async event => {
        event.preventDefault();
        const name = row.elements.name;
        const price = row.elements.price;
        if (!name.value.trim()) {
            feedback.textContent = 'Enter an item name.';
            name.focus();
            return;
        }
        const controls = [...row.querySelectorAll('input, button')];
        controls.forEach(control => { control.disabled = true; });
        feedback.textContent = 'Saving...';
        try {
            const response = await fetch(`/api/menu/${row.dataset.id}`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name.value.trim(), price: Number(price.value) })
            });
            const item = await response.json();
            if (!response.ok) throw new Error(item.error || 'Could not save the menu item.');
            name.value = name.defaultValue = item.item_name;
            price.value = price.defaultValue = Number(item.current_price).toFixed(2);
            feedback.textContent = 'Saved';
        } catch (error) {
            feedback.textContent = error.message;
        } finally {
            controls.forEach(control => { control.disabled = false; });
        }
    });
});
