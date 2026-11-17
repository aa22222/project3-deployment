(() => {
    const panel = document.getElementById('weather-data');
    const message = document.getElementById('weather-status');
    const button = document.getElementById('weather-refresh');
    const content = document.getElementById('weather-content');
    async function loadWeather() {
        button.disabled = true;
        panel.setAttribute('aria-busy', 'true');
        message.textContent = 'Loading weather…';
        content.replaceChildren();
        try {
            const response = await fetch('/api/weather', { signal: AbortSignal.timeout(8000) });
            if (!response.ok) throw new Error('Weather unavailable');
            const weather = await response.json();
            const clock = new Intl.DateTimeFormat('en-US', { timeZone: weather.timezone, weekday: 'short', hour: 'numeric', minute: '2-digit' });
            const temperature = value => `${Math.round(value)}°F`;
            const current = document.createElement('p');
            current.className = 'weather-current';
            current.textContent = `${temperature(weather.current.temperature)} · ${weather.current.condition}`;
            const updated = document.createElement('p');
            updated.className = 'weather-updated';
            updated.textContent = `Updated ${clock.format(new Date(weather.current.time * 1000))} · College Station time`;
            const heading = document.createElement('p');
            heading.textContent = 'Next three hours';
            const list = document.createElement('ul');
            weather.forecast.forEach(hour => {
                const item = document.createElement('li');
                item.textContent = `${clock.format(new Date(hour.time * 1000))}: ${temperature(hour.temperature)} · ${hour.condition}`;
                list.append(item);
            });
            content.append(current, updated, heading, list);
            message.textContent = 'Weather loaded.';
        } catch {
            message.textContent = 'Weather unavailable. You can still order. Try again shortly.';
        } finally {
            button.disabled = false;
            panel.setAttribute('aria-busy', 'false');
        }
    }
    button.addEventListener('click', loadWeather);
    loadWeather();
})();
