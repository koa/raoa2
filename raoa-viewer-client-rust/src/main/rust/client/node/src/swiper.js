// Test import Swiper JS
import Swiper from 'swiper/bundle';
import {Navigation, Pagination} from 'swiper/modules';
// import Swiper and modules styles

// import function to register Swiper custom elements
import {register} from 'swiper/element/bundle';
// register Swiper custom elements
register();

export {Swiper, register}

export function swiper_of_element(element) {
    console.log("Element", element);
    return element.swiper;
}

export function init_with_element(element) {
    return element.swiper;
}




