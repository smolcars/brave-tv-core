# TV webpage loading feedback

Mode: Operate. Platform: native Android TV only. Scope: display-only selected-tab
loading status; extend the existing TV presentation. The user explicitly chooses
a compact indicator and progress bar so partially loaded pages remain usable.
Code-led; no raster assets, comp, new visual identity or phone redesign.

## Direction contract

THESIS: After submitting an address, the TV immediately confirms that navigation
is working while leaving the page usable as content arrives.

INCUMBENT: TvPresentation's charcoal surface, warm text, sage primary color,
Material TV typography and12dp corners. PRODUCT.md/DESIGN.md describe the phone
companion only and are not native TV authority; preserve those files unchanged.

FIRST VIEWPORT: At1920×1080/density320, a300dp-wide opaque surface is inset48dp
from the end edge and32dp from the top. A localized “Loading page…” label sits
above a4dp linear progress indicator, with20dp padding and12dp separation.
It stays readable on bright or dark sites without dimming the page or taking
focus. Width is bounded by the host; sp text follows the system font scale.

STATES: Indeterminate at initial zero progress, then native engine progress.
Disappear when loading stops, including cancellation/failure. Background-tab
events never change the selected status. Hide for native ownership, pause or
locked private mode; re-evaluate actual loading on return. No URL/title is shown.
Completion describes the browser load, not a site's later JavaScript readiness.

SIGNATURE: One calm linear progress signal. No fake percentage, waiting animation
over content, skeleton for unknown website structure, status button or forced
loading duration. Use Material's progress behavior and system motion settings.

QUALITY BAR: Legible from a TV viewing distance; no new remote action or blocked
clicks; visible pending/partial states; reliable completion/cancellation; enlarged
text fits; native/security windows remain the owner. Real APK screenshots and
engine tests are evidence; synthetic fixture checks alone are not acceptance.
