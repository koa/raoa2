import {nodeResolve} from '@rollup/plugin-node-resolve';
import replace from '@rollup/plugin-replace';
import {terser} from 'rollup-plugin-terser';

export default {
    input: "src/swiper.js",
    output: [
        {
            file: "../target/dist/debug/swiper.js",
            format: "esm",
            compact: false,
        },
        {
            file: "../target/dist/release/swiper.js",
            format: "esm",
            compact: true,
            plugins: [terser()]
        }
    ],
    plugins: [
        nodeResolve(),
        replace({
            "preventAssignment": true,
            "values": {
                "process.env.NODE_ENV": JSON.stringify('production')
            }
        }),
    ]
}