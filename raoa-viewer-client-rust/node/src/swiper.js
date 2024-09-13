// Test import Swiper JS
import Swiper from 'swiper/bundle';
import {register} from 'swiper/element/bundle';

register();

export {Swiper, register}

export function swiper_of_element(element) {
    return element.swiper;
}


