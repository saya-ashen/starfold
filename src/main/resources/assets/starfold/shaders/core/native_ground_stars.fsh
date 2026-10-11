#version 150
uniform vec4 ColorModulator;
in float spaceFacing;
in vec4 vertexColor;
out vec4 fragColor;
void main() { if(spaceFacing < 0.0) discard; fragColor=vertexColor*ColorModulator; }
