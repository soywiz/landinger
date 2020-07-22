---
layout: null
permalink: /youtube
---

{% for youtube in youtube_info(['mfJtWm5UddM', 'fCE7-ofMVbM']) %}
{{ youtube.durationStr }}
{% end %}