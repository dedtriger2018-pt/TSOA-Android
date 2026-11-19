package com.winlator.renderer;

/** Production GLSL for the optional presentation passes. */
final class PresentationShaders {
    private PresentationShaders() {}

    private static final String HEADER = String.join("\n",
            "precision highp float;",
            "uniform sampler2D screenTexture, statistics;",
            "uniform vec2 origin, extent, grade, style;",
            "uniform float smoothing;",
            "in vec2 vUV;",
            "layout(location=0) out vec4 outFragColor;",
            "float lum(vec3 c) { return dot(c,vec3(0.2126,0.7152,0.0722)); }");

    static String ambientCaptureShader() {
        return HEADER + String.join("\n", "",
                "uniform vec2 stepUV;",
                "void main() {",
                "    vec3 color=vec3(0);",
                "    for(int y=-1;y<=1;++y) for(int x=-1;x<=1;++x) {",
                "        vec2 p=clamp(vUV+stepUV*vec2(x,y),stepUV*1.5,1.0-stepUV*1.5);",
                "        color+=texture(screenTexture,origin+p*extent).rgb;",
                "    }",
                "    outFragColor=vec4(color/9.0,1);",
                "}");
    }

    static String ambientGaussianShader() {
        return HEADER + String.join("\n", "",
                "uniform vec2 stepUV;",
                "void main() {",
                "    vec3 color=vec3(0.0); float total=0.0;",
                "    for(int i=-12;i<=12;++i) {",
                "        float x=float(i); float w=exp(-0.5*x*x/(smoothing*smoothing));",
                "        color+=texture(screenTexture,vUV+stepUV*x).rgb*w; total+=w;",
                "    }",
                "    outFragColor=vec4(color/total,1);",
                "}");
    }

    static String borderShader() {
        return HEADER + String.join("\n", "",
                "void main() {",
                "    outFragColor=texture(screenTexture,vUV);",
                "    vec2 p=(vUV-origin)/extent;",
                "    if(all(greaterThanEqual(p,vec2(0)))&&all(lessThan(p,vec2(1)))) return;",
                "    if(grade.x<=0.0 || style.x<=0.0) { outFragColor=vec4(0,0,0,1); return; }",
                "    vec2 edge=clamp(p,0.0,1.0);",
                "    vec2 spill=p-edge;",
                // Mirror inward gradually. The entire source patch has already been blurred in both axes.
                "    vec2 reflected=clamp(edge-0.76*spill,0.0,1.0);",
                "    reflected=mix(vec2(0.5),reflected,0.87);",
                "    vec3 color=texture(statistics,reflected).rgb;",
                "    color=mix(vec3(lum(color)),color,style.y);",
                "    float falloff=1.0-0.56*smoothstep(0.0,0.65,length(spill));",
                "    outFragColor=vec4(clamp(color*grade.x*style.x*falloff,0.0,1.0),1);",
                "}");
    }
}
