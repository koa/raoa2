use std::marker::PhantomData;

pub trait RevokedElement<E> {
    fn set(&mut self, element: E);
}
impl<E> RevokedElement<E> for &mut Option<E> {
    fn set(&mut self, element: E) {
        let _ = self.insert(element);
    }
}
pub struct IteratorSegment<
    'a,
    E: Clone,
    I: Iterator<Item = E>,
    C: SameSegmentCondition<E>,
    F: RevokedElement<E>,
> {
    first_entry: Option<E>,
    iterator: &'a mut I,
    condition: C,
    next_failed_element: Option<F>,
}

impl<'a, E: Clone, I: Iterator<Item = E>, C: SameSegmentCondition<E>, F: RevokedElement<E>>
    IteratorSegment<'a, E, I, C, F>
{
    pub fn new(
        first_entry: Option<E>,
        iterator: &'a mut I,
        condition: C,
        next_failed_element: Option<F>,
    ) -> Self {
        Self {
            first_entry,
            iterator,
            condition,
            next_failed_element,
        }
    }
}

pub trait SameSegmentCondition<E> {
    fn is_same(&self, next_entry: &E) -> bool;
}

impl<'a, E: Clone, I: Iterator<Item = E>, C: SameSegmentCondition<E>, F: RevokedElement<E>> Iterator
    for IteratorSegment<'a, E, I, C, F>
{
    type Item = E;

    fn next(&mut self) -> Option<Self::Item> {
        if let Some(first_element) = self.first_entry.take() {
            return Some(first_element);
        }
        let overflow_consumer = self.next_failed_element.as_mut()?;
        let element = self.iterator.next()?;
        if self.condition.is_same(&element) {
            Some(element)
        } else {
            overflow_consumer.set(element);
            None
        }
    }
}
pub struct SameSplitCondition<'a, D: Clone, S: Fn(&E) -> D, E> {
    discriminant_value: D,
    extractor: &'a S,
    p: PhantomData<E>,
}

impl<'a, D: Clone, S: Fn(&E) -> D, E> SameSplitCondition<'a, D, S, E> {
    pub fn new(category_entry: &E, extractor: &'a S) -> Self {
        Self {
            discriminant_value: extractor(category_entry),
            extractor,
            p: Default::default(),
        }
    }
}

impl<'a, E, D: Clone + PartialEq, S: Fn(&E) -> D> SameSegmentCondition<E>
    for SameSplitCondition<'a, D, S, E>
{
    fn is_same(&self, next_entry: &E) -> bool {
        self.discriminant_value == (self.extractor)(next_entry)
    }
}
