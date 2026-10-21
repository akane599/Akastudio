#version 300 es
// Shared vertex stage. Places a unit quad on a rectangle given in canvas pixels (top-left origin, y down),
// maps it into the bound target through u_view, and derives the sampling coordinates and the
// rectangle-local position used by the rounded-rect SDF. Mirrors RenderMath (layerUvMatrix, ViewTransform).
precision highp float;

layout(location = 0) in vec2 a_pos;   // unit-quad corner; (0, 0) = top-left of u_dstRect

uniform vec4 u_dstRect;               // left, top, right, bottom in canvas pixels
uniform vec4 u_view;                  // scaleX, scaleY, offsetX, offsetY: canvas px -> target px
uniform vec2 u_targetSize;            // bound target size in pixels
uniform mat3 u_uvMatrix;              // unit-quad position -> texture coordinates

out vec2 v_uv;
out vec2 v_local;                     // offset from the rectangle centre, canvas pixels

void main() {
    vec2 canvasPx = mix(u_dstRect.xy, u_dstRect.zw, a_pos);
    vec2 targetPx = canvasPx * u_view.xy + u_view.zw;
    // Target row 0 is the top of the image; GL clip space has +y up.
    gl_Position = vec4(
        targetPx.x / u_targetSize.x * 2.0 - 1.0,
        1.0 - targetPx.y / u_targetSize.y * 2.0,
        0.0,
        1.0
    );
    v_uv = (u_uvMatrix * vec3(a_pos, 1.0)).xy;
    v_local = (a_pos - 0.5) * (u_dstRect.zw - u_dstRect.xy);
}
