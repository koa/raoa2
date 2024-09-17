use crate::data::storage::AlbumEntry;

pub trait RowIteratorTrait<I: Iterator<Item = AlbumEntry>> {
    fn calculate_rows(self, width: f64) -> RowIterator<I>;
    /*fn calculate_collected_rows<
        IR: Iterator<Item = AlbumEntry>,
        R,
        F: for<'a> FnMut(
            IteratorSegment<
                'a,
                AlbumEntry,
                I,
                SameSplitCondition<
                    'a,
                    Option<(u32, u32, u32)>,
                    impl Fn(AlbumEntry) -> Option<(u32, u32, u32)>,
                    AlbumEntry,
                >,
                &'_ mut Option<AlbumEntry>,
            >,
        ) -> R,
    >(
        self,
        width: f64,
        collector: F,
    ) -> R;*/
}

impl<I: Iterator<Item = AlbumEntry>> RowIteratorTrait<I> for I {
    fn calculate_rows(self, width: f64) -> RowIterator<I> {
        RowIterator {
            iterator: self,
            remainder: None,
            width,
        }
    }
}

pub struct RowIterator<I: Iterator<Item = AlbumEntry>> {
    iterator: I,
    remainder: Option<AlbumEntry>,
    width: f64,
}

impl<I: Iterator<Item = AlbumEntry>> Iterator for RowIterator<I> {
    type Item = ImageRow;

    fn next(&mut self) -> Option<Self::Item> {
        let mut row = Vec::new();
        let mut current_width = 0.0;
        if let Some(entry) = self.remainder.take() {
            current_width += entry.target_width as f64 / entry.target_height as f64;
            row.push(entry);
        }
        for entry in self.iterator.by_ref() {
            current_width += entry.target_width as f64 / entry.target_height as f64;
            if !row.is_empty() && current_width > self.width {
                self.remainder = Some(entry);
                break;
            }
            row.push(entry);
        }
        if row.is_empty() {
            None
        } else {
            Some(ImageRow::new(row.into_boxed_slice()))
        }
    }
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ImageRow {
    images: Box<[AlbumEntry]>,
}

impl ImageRow {
    fn new(images: Box<[AlbumEntry]>) -> Self {
        Self { images }
    }
    pub fn images(&self) -> &[AlbumEntry] {
        &self.images
    }

    pub fn height(&self) -> f64 {
        1.0 / self
            .images
            .iter()
            .map(|e| e.target_width as f64 / e.target_height as f64)
            .sum::<f64>()
    }
}

pub trait BlockIteratorTrait<I: Iterator<Item = ImageRow>> {
    fn calculate_blocks(self, height: f64) -> BlockIterator<I>;
}

impl<I: Iterator<Item = ImageRow>> BlockIteratorTrait<I> for I {
    fn calculate_blocks(self, height: f64) -> BlockIterator<I> {
        BlockIterator {
            iterator: self,
            height,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ImageBlock {
    rows: Box<[ImageRow]>,
}

impl ImageBlock {
    pub fn rows(&self) -> &[ImageRow] {
        &self.rows
    }
}

pub struct BlockIterator<I: Iterator<Item = ImageRow>> {
    iterator: I,
    height: f64,
}

impl<I: Iterator<Item = ImageRow>> Iterator for BlockIterator<I> {
    type Item = ImageBlock;

    fn next(&mut self) -> Option<Self::Item> {
        let mut block = Vec::new();
        let mut height = 0.0;
        for row in self.iterator.by_ref() {
            height += row.height();
            block.push(row.clone());
            if height > self.height {
                break;
            }
        }
        if block.is_empty() {
            None
        } else {
            Some(ImageBlock {
                rows: block.into_boxed_slice(),
            })
        }
    }
}
