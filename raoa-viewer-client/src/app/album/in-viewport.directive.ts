import {AfterViewInit, Directive, Input, TemplateRef, ViewContainerRef} from '@angular/core';


@Directive({
    selector: '[appInViewport]'
})
export class InViewportDirective implements AfterViewInit {
    alreadyRendered: boolean; // checking if visible already
    private downlink = 1000;
    private maxDownlinkSpeed: number;

    constructor(
        private vcRef: ViewContainerRef,
        private tplRef: TemplateRef<any>
    ) {
    }

    @Input() set appInViewport(maxSpeed: number) {
        this.maxDownlinkSpeed = maxSpeed;
    }

    ngAfterViewInit() {
        const connection = (navigator as any).connection;
        if (connection) {
            this.downlink = connection.downlink;
        }
        const alwaysEnabled = (this.maxDownlinkSpeed !== undefined && this.downlink !== undefined && this.downlink > this.maxDownlinkSpeed);
        const commentEl = this.vcRef.element.nativeElement; // template
        const elToObserve = commentEl.parentElement;
        this.setMinWidthHeight(elToObserve);

        const observer = new IntersectionObserver(entries => {
            entries.forEach(entry => {
                this.renderContents(alwaysEnabled || entry.isIntersecting);
            });
        }, {threshold: [0, .1, .9, 1]});
        observer.observe(elToObserve);
    }

    renderContents(isInView) {
        if (isInView && !this.alreadyRendered) {
            this.vcRef.clear();
            this.vcRef.createEmbeddedView(this.tplRef);
            this.alreadyRendered = true;
        } else if (!isInView && this.alreadyRendered) {
            this.vcRef.clear();
            this.alreadyRendered = false;
        }
    }

    setMinWidthHeight(el) { // prevent issue being visible all together
        const style = window.getComputedStyle(el);
        const [width, height] = [parseInt(style.width, 10), parseInt(style.height, 10)];
        !width && (el.style.minWidth = '40px');
        !height && (el.style.minHeight = '40px');
    }
}
