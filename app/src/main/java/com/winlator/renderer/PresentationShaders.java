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

    static String histogramShader() {
        return HEADER + String.join("\n", "",
                "void main() {",
                "    float shadows=0.0, mids=0.0, whites=0.0, temperature=0.0, sum=0.0;",
                "    for(int j=0;j<8;++j) for(int i=0;i<12;++i) {",
                "        vec2 p=(vec2(i,j)+0.5)/vec2(12,8);",
                "        vec2 uv=origin+p*extent, d=extent*vec2(0.009,0.011);",
                "        vec3 c=(texture(screenTexture,uv-d).rgb+texture(screenTexture,uv+d).rgb",
                "               +texture(screenTexture,uv+vec2(d.x,-d.y)).rgb",
                "               +texture(screenTexture,uv+vec2(-d.x,d.y)).rgb)*0.25;",
                "        float y=lum(c); sum+=y;",
                "        shadows+=1.0-smoothstep(0.13,0.22,y);",
                "        mids+=smoothstep(0.20,0.35,y)*(1.0-smoothstep(0.58,0.75,y));",
                "        whites+=smoothstep(0.70,0.93,y);",
                "        temperature+=(c.r-c.b)*smoothstep(0.06,0.26,y);",
                "    }",
                "    vec4 info=vec4(sum/96.0,whites/96.0,0.5+temperature/192.0,(mids+0.15*shadows)/96.0);",
                "    outFragColor=mix(info,texelFetch(statistics,ivec2(0),0),smoothing);",
                "}");
    }

    static String cinematicShader() {
        return HEADER + String.join("\n", "",
                "void main() {",
                "    vec4 src=texture(screenTexture,vUV); outFragColor=src;",
                "    vec2 p=(vUV-origin)/extent;",
                "    if(any(lessThan(p,vec2(0)))||any(greaterThanEqual(p,vec2(1)))||src.a<0.99) return;",
                "    vec4 stats=texelFetch(statistics,ivec2(0),0);",
                "    float y=lum(src.rgb), detail=smoothstep(0.025,0.13,y)*(1.0-smoothstep(0.78,1.0,y));",
                "    float amount=grade.x*grade.y/3.0;",
                "    float exposure=clamp((0.37-stats.r)*0.54,-0.13,0.12)*(1.0-0.5*stats.g);",
                "    float contrast=(y-0.46)*(0.028+0.11*stats.a);",
                "    vec3 tint=vec3(0.85,0.12,-0.85)*clamp(stats.b-0.5,-0.18,0.18)*0.29;",
                "    vec3 rgb=src.rgb+(src.rgb*(exposure+contrast)+tint)*amount*detail;",
                "    float vignette=smoothstep(0.31,0.71,length(p-0.5));",
                "    rgb*=1.0-vignette*style.x*0.15*amount;",
                "    float grain=fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(0.06711056,0.00583715))))-0.5;",
                "    rgb+=grain*style.y*detail*0.024*amount;",
                "    outFragColor=vec4(clamp(rgb,0.0,1.0),src.a);",
                "}");
    }

    static String borderShader() {
        return HEADER + String.join("\n", "",
                "void main() {",
                "    vec4 src=texture(screenTexture,vUV); outFragColor=src;",
                "    vec2 p=(vUV-origin)/extent;",
                "    if(all(greaterThanEqual(p,vec2(0)))&&all(lessThan(p,vec2(1)))) return;",
                "    vec2 q=clamp(p,vec2(0),vec2(1));",
                "    bool side=abs(p.x-q.x)>abs(p.y-q.y);",
                "    float across=side ? q.x : q.y;",
                "    vec2 center=side ? vec2(across*0.84+0.08,q.y) : vec2(q.x,across*0.84+0.08);",
                "    vec2 stepUV=(side ? vec2(0,1) : vec2(1,0))*(0.045+0.115*style.x);",
                "    vec3 color=vec3(0); float weight=0.0;",
                "    for(int i=-3;i<=3;++i) {",
                "        if(abs(i)>int(grade.y)) continue;",
                "        float w=4.0-float(abs(i));",
                "        vec2 uv=origin+extent*clamp(center+stepUV*float(i),vec2(0.014),vec2(0.986));",
                "        color+=texture(screenTexture,uv).rgb*w; weight+=w;",
                "    }",
                "    color/=weight;",
                "    color=mix(vec3(lum(color)),color,style.y);",
                "    float falloff=exp(-5.2*length(p-q));",
                "    outFragColor=vec4(clamp(color*grade.x*0.71*falloff,0.0,1.0),1);",
                "}");
    }

}
