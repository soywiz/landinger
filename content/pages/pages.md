---
layout: default
permalink: /page/{n}
pagination_list: site.posts
pagination_size: 10
---

Page {{ params.n }}

{{ site.pages | length }}

{% for page in site.pages | slice((params.n - 1) * pagination_size, pagination_size) %}
* {{ page.title }}
{% else %}
{{ error() }}
{% end %}

{% for item in site.collections.mycollection %}
* {{ item.color }}
{% endfor %}